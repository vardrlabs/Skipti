# ADR-0014: Not becoming a default SMS handler

Status: Accepted (records refusal of the Truecaller path)

## Context

Truecaller qualifies for SMS permissions because it is a messaging app: it is
the default Phone and SMS handler, with caller ID and spam blocking. The
policy is applied to it exactly as written. The same path is open to SKIPTI
in principle.

## Decision

Refuse that path. Four compounding reasons:

1. A default handler must actually be one: send, receive, threads, MMS, RCS,
   dual SIM, scheduled send, search, blocking, backup — not a stub.
2. The user would have to make an expense tracker their messaging app. That
   is a lifestyle change, not a permission prompt.
3. It inverts the security posture: from "we hold your encrypted ledger" to
   "we hold your ledger and your entire message history, permanently, as our
   core function."
4. It doubles attack surface and maintenance for a single-developer MIT
   project; every carrier quirk becomes a bug report.

This decision is recorded so a future contributor does not reopen it as a
good idea without engaging these reasons.

## Consequences

Automatic capture on Android comes from the notification listener path
(ADR-0015), not from becoming a handler.
