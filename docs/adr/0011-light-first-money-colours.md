# ADR-0011: Light theme first, and the money colour pair

Status: Accepted

**Amended by ADR-0042 (D-232):** a new install opens in Aurora dark, and since D-212 dark is no longer derived from
light (each token set is its own measured column); light stays a designed, measured requirement.

## Context

A finance app is opened outdoors, at a restaurant table, one-handed, in a
hurry. Dark-only is a posture; light mode is a requirement. Building dark
first produces a light mode that looks like a dark mode with the lights on.

Red/green for money fails ~1 in 12 men (deuteranopia/protanopia).

## Decision

Build the light theme first; derive dark from it. House language: hairline
rules as the primary structural device, uppercase letterspaced micro-labels,
monospace tabular figures for every number, terse declarative copy.

Money colours: warm rust (`owed`) and cool teal-green (`owing`),
distinguishable under deuteranopia and protanopia, verified in simulation.
The sign is always printed (`-₹1,240`, `+₹340`) so colour is never the sole
carrier. Red/green are never used as the money pair.

Real design tokens replace spec proposals before Phase 7; the spec's hex
values are explicitly marked proposals, not scraped values.

## Consequences

- Every chart has a table equivalent; contrast checked per token pair
  actually used, both themes.
- Self-hosted fonts only — a font request tells a third party a user opened
  their finance app.
