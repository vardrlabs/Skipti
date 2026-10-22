# SECURITY.md

## Reporting

Report vulnerabilities to **security@vardrlabs.com**. If email is unsuitable,
open a GitHub security advisory on this repository.

## Response window

- Acknowledgement within **48 hours**.
- Triage and severity assignment within **7 days**.
- Fix or documented mitigation for critical issues targeted within **30
  days**; high within 60; others best-effort with public tracking.
- Coordinated disclosure. We ask for 90 days before public disclosure;
  we will not take legal action over good-faith research.

## Scope

In scope: `core/`, the iOS and Android shells, SKIPTI's Firebase project
(`skipti-26`: its security rules and Cloud Functions), the pages served at
`skipti.vardrlabs.com`, the operator's approvals page (`firebase/admin/`, its
own Hosting site), and every claim made in GUARANTEES.md and
THREAT_MODEL.md. A demonstration that a claim in either document is false is
in scope even if no code is exploitable. (`web/` carries no code yet; it
enters scope when it does.)

Out of scope: anything listed under "Explicitly out of scope" in
THREAT_MODEL.md (rooted devices, coercion, shoulder surfing, screenshots by
group members), and automated scanner noise without a demonstrated impact.

## Safe harbour

Good-faith research against your own instances or data, respecting other
users, is welcome and will not be pursued legally.

## Claims discipline

Every user-facing security sentence must pass three checks before shipping
(SPEC §14.6): true given the threat model; still true if the reader is a
malicious operator; states its own limit nearby. Banned phrasings include
"bank-grade", "military-grade", "unhackable", "100% private", "fully
anonymous", and compliance badges used as marketing.

## Supported versions

Only the latest release of each platform target receives security fixes.
The record format is versioned (ADR-0018, ADR-0034); breaking record-format
changes ship behind a version bump.
