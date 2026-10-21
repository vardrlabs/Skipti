# ADR-0012: No analytics, ads, attribution, or session replay — ever

Status: Accepted

## Context

Every one of these SDKs transmits user data off-device and requires privacy
manifests declaring tracking. Recording a finance app's screens is grotesque.
"No telemetry" is a written guarantee (GUARANTEES.md), and the only
enforceable way to keep it is to ship no code that can transmit it.

## Decision

Hard cap: 30 direct production dependencies per platform; every addition
after freeze needs a PR justification (what it does, who maintains it,
release cadence, what it replaces).

Banned outright, enforced in CI by manifest/dependency scan (`deny.toml`,
npm audit policy):

- All analytics SDKs (Firebase Analytics, Amplitude, Mixpanel, Segment).
- All crash reporters that transmit off-device by default. Crash reports are
  opt-in, scrubbed, and user-visible before sending, or absent.
- All ad and attribution SDKs (AppsFlyer, Adjust, Branch).
- All session-replay tools.
- Any dependency requiring a tracking-declaring privacy manifest.

This list is a feature and lives in the README's guarantees.

## Consequences

Diagnostics come from opt-in, scrubbed local logs. The egress audit
(SPEC §14.4 proof 5: exactly one destination host per session) is the
standing test of this ADR.
