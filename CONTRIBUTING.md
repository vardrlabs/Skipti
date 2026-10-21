# Contributing to SKIPTI

Read `docs/SPEC.md` §2 (threat model) and §12 (store policy) first: they
override all other instructions where they conflict. The phase gates in
SPEC §15 are real; do not start a phase until the previous gate is signed
off.

## Standing rules (from SPEC §16)

1. Never invent a file path; if one does not exist, stop and report the
   actual tree.
2. If an instruction conflicts with `docs/adr/`, stop and report rather than
   silently resolving.
3. Never weaken a security control to make a test pass.
4. Never add a dependency without stating what it does, who maintains it,
   its release cadence, and what it replaces. Hard cap after freeze:
   30 direct production dependencies per platform.
5. Money is always integer minor units plus an ISO 4217 currency code. If
   you write a float in the money path, stop.
6. If you are unsure whether something is a security control, assume it is.

## Legal boundary (ADR-0008)

SKIPTI records debts; it never moves money; it never holds funds; it never
links bank accounts by credentials; it never offers credit or advice. A PR
that would change any of these is closed on sight. This is the line between
a notebook and a regulated financial institution.

## Definition of done (per module, SPEC §18.3)

1. It has tests, and at least one is a negative test.
2. It has no TODO marking a security control.
3. Every secret it touches is wrapped, wiped, or non-extractable.
4. It logs nothing derived from a transaction.
5. Its section of the master checklist is fully ticked.
6. Its user-facing copy has passed the SPEC §14.6 claims filter.

## The gate

One command decides whether the tree is green. Run it before claiming any
progress, and paste its evidence block into `docs/BUILD-LOG.md` §3:

    make hooks       # once per clone: installs the pre-commit hook
    make gate        # ~10s warm. fmt, clippy, ADR-0002 float audit, tests, wasm
    make gate-full   # adds release property sweeps, cargo audit, cargo deny

`make gate` is also what the pre-commit hook runs, so a red tree cannot be
committed by accident. `git commit --no-verify` bypasses it; if you use that
to record a deliberately known-red state, say so in the commit message.

A gate whose tooling is missing reports **SKIP**, never a pass, and the summary
counts skips separately. "Passed with skips" is not a green run. This follows
the same rule as the CI no-ops in BUILD-LOG D-23: green must mean checked.

### Why this exists

A tree once sat with 87 passing tests, a failing `cargo fmt`, a failing
`cargo clippy`, and a BUILD-LOG confidently describing a third state. The
clippy error was not cosmetic: it was flagging a verified account identity
being discarded in `passkey_auth_finish`, because sessions carried no subject.
Passing tests hid it. The gate is cheap; discovering that class of bug three
phases later is not.

## Process

- Decisions go through `docs/adr/`; add a new ADR rather than contradicting
  an accepted one.
- CI must be green: tests, clippy zero-warnings, cargo audit, cargo deny,
  dependency scan for banned SDK categories (ADR-0012). `make gate-full` runs
  the same checks locally, so CI should never be the first place you learn
  the tree is red.
- Commits are conventional and small; no plaintext fixtures with real
  financial data, ever.
- Commit as you go. A phase that lands as one giant untracked tree cannot be
  reviewed, bisected, or handed to another agent mid-flight.
