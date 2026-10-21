# ADR-0020: Apache-2.0 supersedes MIT

Status: Accepted (supersedes ADR-0009)

## Context

ADR-0009 compared MIT against AGPL and chose MIT on App Store distribution
grounds. **Apache-2.0 was never part of that comparison** — so this decision
settles a comparison that was never made; it does not reverse a considered
one. Recorded plainly, because superseding an ADR whose actual alternative
space was {MIT, AGPL} is different from overturning one that weighed three
options.

The founder selected Apache-2.0 over MIT for four reasons, none of which
AGPL-vs-MIT touched:

1. **Express patent grant (§3).** MIT's patent position is implied at best.
   For a finance app that may implement novel merge, padding, or key-rotation
   mechanics, an explicit grant from every contributor is worth having before
   there is anything to litigate over.
2. **Patent retaliation (§3).** The grant terminates for anyone who files
   patent litigation claiming the Work infringes. MIT has no such clause.
3. **Trademark non-grant (§6).** MIT's blanket permission plus attribution
   arguably permits forks to ship under confusingly similar names once
   SKIPTI is a store listing. Apache-2.0 explicitly does not grant trademark
   rights, which is the protection the brand needs at exactly the moment it
   starts to matter.
4. **Contribution terms (§5).** Inbound-is-outbound reduces the need for a
   CLA while outside contributions remain zero — which they are, and which
   is also what makes this relicensing possible at all.

**Relicensing honesty.** Every commit in this repository is the founder's;
there are no outside contributors and no released binaries. That is why this
is clean today, and it gets harder with each outside contribution and each
shipped build. ADR-0009 said licence changes arrive "by fork, not by quiet
relicence of existing releases" — honoured here: no release exists to strip
terms from, so nothing distributed is being retroactively relabelled. This
is the last cheap moment, which is why the change lands now rather than
after v1.

**The one real cost:** Apache-2.0 is incompatible with GPLv2-*only* where
MIT is compatible. Forks targeting GPLv3 or later are unaffected; anyone
locked to GPLv2 loses the option. Accepted knowingly as the price of the
four gains above.

## Decision

All project crates and original documentation are licensed **Apache-2.0**,
effective immediately:

- `LICENSE` carries the full canonical Apache-2.0 text.
- Workspace `license` field becomes `Apache-2.0` (crates inherit via
  `license.workspace`).
- `NOTICE` adopts Apache NOTICE conventions and retains its third-party
  provenance sections (BIP-39 wordlist origin, trademark identifications) —
  those record facts about *other* works' licences and are not touched.
- `README.md` states Apache-2.0.
- SPEC §13.1 carries an annotation pointing here; the rest of the SPEC's
  MIT mentions are historical records of phase prompts and recommendations
  and are deliberately left as written.
- `deny.toml` needed **no change**: its allow-list already permitted
  Apache-2.0 for dependencies (e.g. D-39's rusqlite justification cites
  MIT for rusqlite itself — that stays; it describes upstream crates).

## Consequences

- Patent grant and retaliation now protect both the project and its users;
  the SKIPTI name is not licensed to forks once it is a store listing.
- Inbound contributions default to Apache-2.0 without a separate agreement.
- GPLv2-only incorporation is newly impossible; documented here so nobody
  discovers it in a licence audit.
- Every future contributor must be shown this ADR: after outside commits
  land, relicensing again requires their consent, not just the founder's.
