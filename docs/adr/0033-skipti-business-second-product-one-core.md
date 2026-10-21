# ADR-0033: SKIPTI Business — a second product on one core

Date: 2026-08-29
Status: Accepted (founder-decided 2026-08-29; four forks answered directly)

## Context

The founder needs Vardr Labs' own finances in one place, and wants the web
shell to serve that purpose: colleagues signing in with an `@vardrlabs.com`
address land in a company environment, expenses they file are visible to the
admin who processes them, and the product eventually issues invoices that
satisfy US requirements.

That is not a feature. It collides with four things this project has already
committed to in writing, and the collisions are the reason this ADR exists
rather than a paste.

| Commitment | Collision |
|---|---|
| GUARANTEES #1/#3 — no subscription, no paid tier, ever | Business invoicing is ongoing work that wants funding |
| GUARANTEES #6 — no third-party network origin | Tax data has to come from somewhere |
| GUARANTEES #7 — we cannot read your data | An admin reading employees' filed expenses is a new reader |
| ADR-0019 — one app, splitting first | A business tier is a third scope, not a later phase of the second |

`§14.6`'s claims filter adds a fifth: **"legally enforceable invoice" is not
a claim software can make.** Enforceability depends on the issuer's
registration, the jurisdiction and retention practice — not on the document
generator. The filter would reject the phrase, and it is right to.

## Decision

### 1. Two products, one core

**SKIPTI stays exactly as guaranteed.** Every sentence in GUARANTEES.md keeps
meaning what it says, unqualified, with no asterisk. This is the whole point:
those guarantees are the project's most distinctive asset, and a product that
amends them under commercial pressure has already spent them.

**SKIPTI Business is a separate product** — its own name, listing, terms and
privacy policy — sharing the Rust core, the relay, the design system and the
shells' architecture. It may charge. It may talk to a tax-data origin. Those
are its terms to set, and they bind nothing on the personal side.

Rejected: one app with scoped guarantees. It buys unification at the cost of
turning seven unqualified promises into seven promises with a footnote, and
the footnote is the part users would remember.

### 2. An org is a GROUP. The crypto is already built.

This is the finding that makes the whole thing tractable, and it was verified
against the tree before this ADR was written.

`lykill` already provides exactly the primitive an org needs:

- `wrap_group_key_for` / `unwrap_group_key` — a group key sealed per
  recipient device, bound to `(group_id, epoch)`
- `rotate_group_key` — epoch rotation that re-wraps for survivors only
- proven adversarially: `rotation_wraps_survivors_only_and_a_revoked_device_cannot_follow`
  (A-12, D-37) — a removed member holding every stolen envelope still cannot
  read the next epoch

So: **an org is a group; the admin is a member holding the group key.** An
employee leaving is a member removal, which rotates the epoch and locks them
out of everything filed afterwards. No new cryptography is required, and none
should be invented.

**GUARANTEES #7 therefore survives even on the business side.** The server
still stores ciphertext it cannot open. The admin can read filed expenses
because the admin *holds a key*, not because the relay was weakened. That
distinction is the entire difference between "encrypted within the company
environment" and "not really encrypted".

### 3. Personal and filed data are different things

An employee's personal ledger stays theirs, sealed under their own keys, and
the admin never sees it. Only what they **file to the org** is sealed to the
org group. The UI must make which side you are on unmistakable at the moment
of entry, not in a settings screen.

### 4. Invoicing: US only, compliant-format, no enforceability claim

Vardr Labs LLC is a New York entity, so US is the real target. The data model
is designed so GST fields (GSTIN, HSN/SAC, place of supply) can be added
without migration, because INR-first formatting means India is a plausible
second jurisdiction — but nothing GST-specific is built now.

The wording that ships is **"carries every field US invoicing requires"**,
never "legally enforceable". The founder asked for full compliance on the
business side and format-only on the personal side, and that is what this
grants: compliance of FORM, stated precisely, with the enforceability
question left where it belongs — with the issuer and their accountant.

## Two hazards found while analysing this, both real

### Domain matching is not authorisation

"Anyone with an `@vardrlabs.com` address" is a dangerous membership rule.
OTP proves control of a mailbox, not employment. A former employee whose
mailbox outlives their exit would keep joining the org, and a mis-configured
catch-all would let anyone in.

**Membership must be admin-granted, with the domain as a hint and never as
the authorisation.** The invite path is the group-envelope path that already
exists: an admin wraps the org key for a new member's device. Nobody joins by
asserting an address.

### Offline-first and gapless invoice numbering are in direct tension

Invoice numbers are conventionally sequential and gapless. SKIPTI is
offline-first by design (§10.3, GUARANTEES #5). Two devices creating invoices
offline cannot both produce the next number without coordination.

Three honest options, none free:

1. **Server-assigned numbers** — correct sequence, but invoice creation now
   requires connectivity, which contradicts offline-first for that one
   action.
2. **Per-issuer prefixes** (`VL-A-0001`, `VL-B-0001`) — stays offline, and
   remains defensible as long as each series is itself gapless.
3. **Reserved blocks** — a device claims a range while online and spends it
   offline. Preserves both properties at the cost of gaps when a block is
   abandoned.

**No option is chosen here.** It is a genuine fork, it needs the founder, and
pretending otherwise would bake a compliance decision into an implementation
detail. Recorded so it is decided deliberately rather than discovered.

### And a third: retention

An invoice you lose is a compliance problem, not an inconvenience. Local-first
with optional sync is right for expenses and insufficient for invoices.
Business-side retention needs its own answer, and ADR-0030's backup policy is
the model — not a new invention.

## Consequences

- Two listings, two privacy policies, two support surfaces. Real overhead,
  accepted as the price of leaving GUARANTEES unqualified.
- The web shell's foundation work is **unchanged and still correct**: scaffold,
  wasm bindings and the balance line are shared substrate either way. Business
  divergence begins after it.
- `web/` becomes the first surface where both products exist, so the shell
  must carry a product boundary from early on rather than acquiring one later.
- Nothing in this ADR is built yet. It records the fork and the two hazards;
  the build order stays the founder's.

## Amendment, 2026-08-29 — the business surface is PRIVATE, and that is simpler

Founder clarification, same day: **the business side is not marketed, not
sold, and not public. It is his own company's instance.** That collapses most
of the tension this ADR was written to manage, and the ADR is corrected in
place rather than rewritten, because the reasoning above is what makes the
correction safe.

What changes:

- **No second listing, no second privacy policy, no second support surface.**
  The "real overhead, accepted" consequence above is **withdrawn** — it was
  priced against a public product that is not being built.
- **GUARANTEES #1/#3 are not strained at all.** Nobody is charged, so there
  is no subscription and no paid tier. The collision this ADR opened with was
  hypothetical once the business surface stopped being a product.
- **GUARANTEES #6 holds by construction**, not by scoping: business code and
  any tax-data origin **do not ship in the public app**. A guarantee about
  what the app talks to is trivially true when the code is not in the app.

What does NOT change: the group-key model, the admin-holds-a-key distinction,
GUARANTEES #7 surviving, and §14.6 governing invoice wording. Those were right
for reasons independent of who the product is sold to.

**The accurate framing is now: one public product, plus a private business
surface on the same core** — not two products. The title of this ADR is left
as written so the change of shape stays visible in the log.

### The three forks, resolved by the founder

1. **Membership is invite-only.** Confirmed: a person receives a specific
   invite to the business suite. The domain is a hint and never the
   authorisation — the hazard above stands as written and is now the rule.
2. **Invoice numbering: server-assigned.** The business surface is
   always-connected by decision, so option 1 is taken and its cost —
   invoice creation requires connectivity — is accepted deliberately. This
   does not weaken offline-first anywhere else: expenses, balances and
   settlement remain fully offline (GUARANTEES #5 untouched).
3. **Invoices carry an approval lifecycle**, which the numbering decision
   makes coherent: anyone may create, an admin approves. States are
   `created → awaiting approval → approved | rejected`, tracked in real
   time. An admin dashboard shows every invoice in every state; **an employee
   sees only their own.** That visibility split is the same group/personal
   boundary this ADR already draws, applied to a second object type.

Deferred and named rather than designed: digital document signing, PDF
export, and whatever else the lifecycle grows. Not in scope now.

### One question left open

The founder's note that "the non-business side won't have issues with the gap
in their invoices" implies personal invoicing exists. Everything else said so
far — "nothing changes on the people side, only the business side" — implies
it does not. **Recorded as unresolved rather than guessed.** If personal
invoicing is wanted, it is its own decision: it would inherit none of the
server-assigned numbering above, because personal SKIPTI must stay usable
offline.

Related and already open: ADR-0007 lists **Google Sign-In** and nothing
implements it. The founder's phrasing assumes Google/Apple sign-in exists.
That gap is real and still the founder's call.

## Tripwires

Reopen this ADR before shipping if any of these becomes true:

- A GUARANTEES sentence acquires a qualifier, footnote or asterisk.
- Business features, or any tax-data origin, appear inside the shipped
  public app. The amendment's guarantee-safety rests entirely on that
  code not being there.
- Org membership is granted by email domain alone.
- Any invoice copy says "legally enforceable", "guaranteed compliant", or
  equivalent — §14.6 governs the business product too.
- The relay gains the ability to read org data. Admin access is a KEY the
  admin holds, never a capability the server acquires.
