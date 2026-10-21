# ADR-0019: One app, splitting first — personal finance lands after the shells

Status: Accepted (founder sign-off received; settles SPEC §17.4)

## Context

SPEC §17.4 is marked "no phase, decide early" and, four phases and one
encrypted store later, is the only §17 decision with no ADR. It is not a
philosophical question; it is a build-order question. The master checklist
runs 5 (ingestion) → 6 (categories/analytics/export) → 7 (shells) → 8
(compliance), and Phases 5–6 are largely personal-finance surface. Whichever
way §17.4 lands determines whether anything in those phases should be built
before a single screen exists.

**The case for doing both at v1** (the differentiator argument): SKIPTI's
README already promises "personal finance and shared expense splitting" as
one product; shipping half of it weakens the pitch, and the completionist
order keeps every gate landing in sequence with no rescoping churn. But this
position collides with three facts in the tree and the spec itself:

1. §17.4's own words: doing both is "two products' worth of surface area,"
   which is exactly what delays a shippable v1 by the two largest remaining
   core phases.
2. The checklist order is already entangled, not sequential: receipt OCR
   (Phase 5) is Vision/ML Kit/Tesseract work that requires the Phase 7
   shells to exist first, and the Android Direct-flavour rows are blocked on
   §17.3, which nobody has decided. "Both at v1" cannot actually be built in
   5→6→7→8 order.
3. GATE 1 — the proof of record — is a *splitting* proof: 600 expenses,
   settlements, simplification plans. The mature, audited engine in this
   tree is the splitting engine (`hlutr`, `norn`, D-01…D-08).

**The case for two apps**: focus per store listing and a halved compliance
surface per binary. It fails here: the core, crypto, sync protocol, and
server (§6.4 deliberately minimal) are identical underneath; two apps would
mean two shells and two brand surfaces over one engine — double maintenance,
not double value. Rejected.

**The hedge's real cost, engaged.** The spec's recommendation — splitting
first, personal ledger architecturally present from day one — sounds like
having it both ways, and hedges always deserve suspicion: present-but-
unshipped is still schema, still branches, still maintenance. Measured
against the tree, the hedge turns out to be nearly free *here*:

- `core/src/skrin/schema_v1.sql:16-17`: records carry `personal INTEGER
  NOT NULL` and nullable `group_id BLOB`. A personal record is `(1, NULL)` —
  representable in v1 today, no migration (`skrin/mod.rs:202-203` maps
  `Scope::Personal` to exactly that).
- `straumr::Scope::Personal` is a first-class scope variant in the wire
  header; `lykill::derive_personal_dek` (with `INFO_PERSONAL`) already
  derives the personal content key.
- Record payloads are opaque sealed blobs behind header columns (D-39), so
  personal-mode op types arrive as data, not as schema changes.

What the hedge genuinely costs: the personal key path ships unexercised by
any user flow until personal mode lands, and every future screen must branch
on mode. That is bounded, ongoing vigilance — not retrofit risk. By contrast,
integrating Automerge-style retrofits into a frozen store *was* the expensive
kind, which is why ADR-0018 insisted on settling these questions before
skrin; this one was, and it turns out the store already answers it.

## Decision

(RECOMMENDATION — founder sign-off pending.)

One app. v1 ships splitting first; personal finance lands as updates after
the shells exist. Concretely, the build-order consequence is:

| Checklist item | Disposition |
|---|---|
| Phase 7 (shells, UniFFI/wasm wiring, design system, accessibility) | Pulled forward to follow Phase 4 directly |
| Phase 5 · Splitwise import | Pulled forward as the immediate post-v1 fast-follow — it is the wedge's migration path and deferring it hollows out the §17.4 market argument |
| Phase 5 · everything else (CSV/OFX/QIF/MT940, receipt OCR, share-sheet, widget, SMS Direct flavour) | Deferred wholesale; SMS rows additionally blocked on §17.3 |
| Phase 6 · XLSX/CSV export of groups | Pulled forward to pre-v1 scope: GUARANTEES promises full export, and a splitting-only v1 must keep that promise for the data it does hold |
| Phase 6 · merchant table, rules, classifier, nine charts, budgets, PDF/OFX/QIF | Deferred wholesale — predominantly personal-mode value |
| GATE 5 / GATE 6 | Move to after GATE 7 in practice; definitions unchanged |

The personal ledger remains architecturally present — it already is, at zero
additional cost (see Context). What is *not* built is any personal-mode
user flow before the shells ship.

## Consequences

- A shippable v1 stops requiring two of the largest remaining phases; time
  to first real users shrinks by roughly the size of Phases 5–6.
- The wedge keeps its teeth: Splitwise import is named as the fast-follow
  rather than lost inside a deferred phase.
- GUARANTEES.md's export promise survives v1 via the pulled-forward export
  rows; it does not silently shrink.
- The hedge is maintained knowingly: personal key derivation and scope stay
  exercised by tests only until personal mode ships; every new screen must
  answer "what does this do in personal mode?" even while the answer may be
  "nothing yet."
- If the founder rejects splitting-first, the fallback is the master order
  (both at v1), accepting that OCR and SMS rows force Phase 7 work to inter
  leave with Phase 5 anyway; two separate apps remain rejected for the
  reasons above.
- This decision settles nothing else: §17.3 (ship an SMS build at all)
  still blocks the Phase 5 Direct-flavour rows; §17.5 (hosted vs self-host)
  still blocks Phase 8; §17.2's cloud-drive-as-default README promise still
  awaits a backend that exists.
