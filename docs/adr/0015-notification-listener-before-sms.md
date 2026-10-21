# ADR-0015: Notification listener before SMS parsing

Status: Accepted (blocks Phase 5 ordering)

## Context

`BIND_NOTIFICATION_LISTENER_SERVICE` reads bank and UPI app notifications
without touching SMS: different permission, different policy surface, no
default-handler requirement, no inbox history to backfill, and a bank
notification is already a summarised transaction with no OTPs in it. It is
also the only automatic-capture path that can plausibly live in a
Play-distributed build, because Play Protect blocks sideloaded apps carrying
SMS permissions (see ADR-0010).

Honest limits: still restricted and reviewable; worse coverage than SMS
(requires the bank app installed with notifications enabled); OEM battery
managers are the dominant operational failure mode in India.

## Decision

Build the notification listener before the SMS parser. Implementation rules:

- Manifest narrows delivery at the OS boundary (`default_filter_types`
  alerting; conversations/ongoing/silent disabled) rather than filtering
  after receipt.
- Prominent in-app disclosure **before** the system settings screen;
  Android 11+ deep-links straight to the app's own entry.
- Source discovery is user-driven: package name + match count only, recorded
  only while the discovery screen is open. Allowlist check runs first, before
  any notification extras are read.
- Forward-only; on-device parsing; derived fields only; complete revocation
  deletes every rule and artefact.
- Visible health indicator ("Capture active · last event 12 min ago") plus
  per-OEM battery-whitelist guidance.

## Consequences

SMS remains a v1.2 reassessment at the earliest (ADR-0010 ship order), and
nothing else depends on it.
