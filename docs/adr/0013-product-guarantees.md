# ADR-0013: Product guarantees, and what would have to be true to change one

Status: Accepted (guarantee #6 amended by ADR-0035, 2026-09-14)

## Context

The guarantees in GUARANTEES.md are the product's spine: no
subscription/ads/telemetry; no caps on the core loop; no paid tiers; one-tap
full export; offline and account-free functionality; single network origin;
operator cannot read data. SPEC §17.6 asks for the sustainability answer to
be written down while it is hypothetical and honesty is cheap.

## Decision

The guarantees are load-bearing and breaking one is a fork-worthy event.

Compatible future funding: donations; a paid hosted sync tier that changes
nothing about the app itself (and publishes its costs); a one-time store
purchase. All three keep the core loop ungated and the ledger private.

Never compatible, regardless of circumstances: ads, data sale, gating or
metering expense logging, telemetry of any kind. A future version of this
project does not get to reverse these quietly — the guarantee file exists
precisely so that reversal is loud.

## Consequences

Any PR touching monetisation must reference this ADR and GUARANTEES.md;
changes require a major-version fork point, not a patch note.
