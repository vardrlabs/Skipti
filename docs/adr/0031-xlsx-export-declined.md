# ADR-0031: XLSX export is declined — §9.2's constraints leave no correct build

Date: 2026-08-26
Status: Accepted (founder pre-authorised either outcome of the rule-4 review)

## Context

SPEC §9.2 names `rust_xlsxwriter` and calls XLSX "the one most people want";
the backlog deferred the dependency decision three times. Rule 4 (CONTRIBUTING)
requires every dependency to justify itself: what it does, who maintains it,
its release cadence, what it replaces. §10.5 caps core at 30 direct production
dependencies; core sits at 12.

The rule-4 fields, honestly:

- **What it does:** writes `.xlsx` (a zip of XML parts: sheets, shared
  strings, styles). Mature, well-documented, chart support.
- **Maintainer:** John McNamara (jmcnamara), sole author, also the author of
  the decade-old Perl `Excel::Writer::XLSX` lineage — long track record.
- **Release cadence:** active, multiple releases in recent months.
- **What it replaces:** nothing — this is a pure addition of capability,
  which under §10.5's logic is the worst kind: additions that replace
  hand-written code amortise their audit surface; pure additions only add it
  (zip + compression crates ride in transitively).
- **Budget:** 12 → 13 of 30. The cap is not the reason. It never was — at 13
  of 30 there is room. The reason is below.

## The decisive finding: §9.2's constraints are jointly unsatisfiable *and* self-defeating

§9.2 binds any XLSX build to three constraints at once:

1. "Number cells are real numbers" — amounts must be numeric cells.
2. "never strings" — no textual workaround.
3. "never floats derived from minor-unit integers by division at write time"
   — so the exact integer 4550 may not become 45.50 in our code.

With those held, the only representable cell value is the **minor-unit
integer itself** (4550) with a format string shifting the decimal point
visually (`"₹"#,##0"."00` → shows ₹45.50). The file then satisfies 1–3
literally. The trap is narrower than "every computation breaks", and
narrower is worse: SUM and AVERAGE are scale-invariant — SUM(4550, 1000) =
5550, which the same shifted format displays as ₹55.50, and Excel usually
propagates the column's format to an AutoSum cell — so the two operations
a sceptical reader tries first are exactly the two that LOOK correct. What
actually breaks, and cannot be formatted around: chart axes and data labels
plot the raw 4550; pivot values and any formula that mixes these cells with
literal numbers read 4550; copying a column to another tool yields 4550. A
sound spreadsheet hides its lie from the first check and springs it later.
That is worse than a string cell, which at least announces itself as data
to be careful with; a mis-scaled number looks authoritative precisely where
it is checked least.

The constraints can only all hold with true decimal values in the cells —
which requires write-time division (banned by 3) or a fixed-point cell type
XLSX does not have (its numbers are binary doubles at the file-format level).
Note the file format itself stores every number as f64; constraint 3 is
therefore not satisfiable by ANY XLSX writer for fractional amounts unless
the value is computed by division at write time. The spec's own named
dependency cannot comply with the spec's own rule.

## Decision

XLSX export is **declined**, and the backlog item is CLOSED, not deferred a
fourth time.

- GUARANTEES #4 ("exportable in full, in an open format, at any time, in one
  tap") is satisfied by the two formats that exist: **JSON** (the lossless,
  schema-versioned backup — the format that makes the guarantee real) and
  **CSV** (RFC 4180, UTF-8 with BOM for Excel, exact decimal text per
  currency exponent — opens in Excel/Numbers/Sheets today). XLSX adds
  audience convenience, not a guarantee.
- §9.2's XLSX row and GATE 6's XLSX clause are superseded by this ADR (the
  ADR-0018 pattern: the record notes the supersession; SPEC text stands as
  written history). PDF export is untouched by this decision.

## Tripwire for revisiting

This decision reopens ONLY if all three of the following hold:

1. A concrete distribution need exists (accountant demand, App Store review
   feedback, a named user workflow that CSV demonstrably fails).
2. Constraint 3 is AMENDED in SPEC §9.2 first — most plausibly to permit
   write-time conversion to a decimal-exact representation, accepting the
   file format's f64 storage as the spec's own naming of `rust_xlsxwriter`
   implicitly does. Shipping the format-string trick WITHOUT that amendment
   stays forbidden: a wrong-by-100× SUM is a correctness defect wearing a
   spreadsheet's clothes.
3. The rule-4 fields above are re-verified (maintainer status, cadence,
   transitives) at reopening time.

Until then: CSV and JSON are the export story, and the backlog item is
closed on the merits rather than left open a fourth time.
