# ADR-0009: MIT rather than AGPL

Status: Superseded by ADR-0020 (Apache-2.0; this ADR compared MIT only against AGPL - Apache was never weighed until now)

## Context

The project licence was specified as MIT. MIT permits anyone to fork,
rebrand, and monetise — deliberate, but worth recording rather than
sleepwalking into. AGPL would prevent forks going closed-source but carries
a long-standing tension between GPL-family terms and App Store distribution
terms.

## Decision

MIT, plus a NOTICE file. For a store-distributed app, MIT is the right call;
the App Store compatibility risk of AGPL is not worth the copyleft benefit
here. If that judgement ever changes, it changes by fork, not by quiet
relicence of existing releases.

## Consequences

- Forks may close-source; the GUARANTEES.md file is the reputational anchor
  users should check against any fork claiming continuity.
- NOTICE records third-party material provenance (BIP-39 wordlist).
