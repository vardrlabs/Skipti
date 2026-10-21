# ADR-0010: Two Android flavours, and the SMS policy position

Status: Accepted

## Context

Google's policy restricts SMS permissions to default handlers and documented
core functionality; established budgeting apps applying for exactly this use
case have been refused, including apps that process everything on-device.
Eligibility turns on what kind of app you are, not what you do with the data.
Additionally, Play Protect blocks sideloaded installs of apps declaring
READ_SMS/RECEIVE_SMS from browser/messaging sources, so "ship a sideload APK"
is not a clean fallback either.

## Decision

Two honestly different builds, clearly labelled:

- `play` flavour: SMS module excluded at compile time; permission absent
  from the manifest entirely — not requested, not declared, not present as
  placeholder text (placeholder strings are themselves a violation). A CI
  check greps the built Play AAB's merged manifest for SMS permissions and
  fails the build if found.
- `direct` flavour (F-Droid/GitHub): READ_SMS + RECEIVE_SMS, opt-in,
  on-device only, under the absolute rules of SPEC §7.3.

The Play build shows honest copy about the limitation with a link to the
direct download; never "coming soon".

Ship order: v1 with no sensitive permission at all; notification capture as
a v1.1 update (ADR-0015); SMS reassessed for v1.2 only if 1.1 was approved
(SPEC §17.3 recommends shipping v1 without SMS entirely).

## Consequences

- The Direct flavour reaches few users because of Play Protect; this is
  accepted and stated rather than worked around.
- Losing capture entirely must break nothing: capture feeds a review inbox
  that also accepts manual entry, share sheet, OCR, and import.
