# ADR-0016: Sensitive permissions ship in an update, never a first submission

Status: Accepted

## Context

A rejection on a first submission can put a new developer account under
broader review. A rejection on an update is an inconvenience. Google also
requires Organisation registration for financial products, which has lead
time of its own.

## Decision

```
v1.0   Manual entry, share-sheet capture, Shortcuts, receipt OCR,
       statement import. No sensitive permissions. Get approved.
v1.1   Notification capture as an update, with disclosure screen,
       Data Safety update, and policy justification prepared in advance.
v1.2   Reassess SMS only if 1.1 was approved and users still ask.
```

The capture pipeline is built behind a feature flag during v1 so v1.1 is a
flag flip plus a manifest entry rather than new architecture under review
pressure.

## Consequences

Launch scope is narrower than the end state by design; the flag discipline
keeps the delta small.
