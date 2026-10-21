# Working on SKIPTI

Read this first, every session. It is deliberately short; everything it points
at is authoritative, and it is not.

## 1. Find out where things actually stand

    git log --oneline | head -20
    make gate

Then read `docs/BUILD-LOG.md` — **§1** for phase status, **§4** for the open gap
register, **§2** for the D-numbered implementation decisions already made. That
file is the handoff document between sessions and is the fastest way to load
state.

Treat it as evidence, not scripture. It has been wrong before: it once claimed
83 tests and "WebAuthn not started" while the tree had passkeys implemented and
two build gates failing. **`make gate` outranks the document.** Where they
disagree, the tree is right and the log needs fixing — fix it as part of your
work.

## 2. How to know you are green

    make gate         # ~10s: fmt, clippy, ADR-0002 float audit, tests, wasm
    make gate-full    # adds release sweeps, cargo audit, cargo deny

`make gate` is the only thing that decides green. A pre-commit hook runs it, so
a red tree cannot be committed by accident. It prints an evidence block — paste
that into `docs/BUILD-LOG.md` §3 when you claim progress. A claim without a
reproducing command belongs in §4 (gaps), not §1 (status).

A gate whose tooling is missing prints **SKIP**, never a pass. "GATE PASSED WITH
SKIPS" is not green.

## 3. Where to look things up

`docs/SPEC.md` is the authority and overrides `CONTRIBUTING.md`, `docs/adr/`,
and `docs/BUILD-LOG.md` wherever they are silent or conflict. It is 2,257 lines.
**Never read it whole and never attach it.** Get the map first, then read ranges:

    grep -n '^#\{1,3\} ' docs/SPEC.md     # 133-line index of the whole spec

Most day-to-day constraints are already distilled and cheaper to read:

- `CONTRIBUTING.md` — standing rules, the legal boundary, definition of done
- `docs/adr/0001`–`0017` — architectural decisions, binding
- `docs/BUILD-LOG.md` §2 — implementation decisions D-01…, this repo only

## 4. Session hygiene

Long sessions in this setup die: replies come back instantly with no text and no
tool call. That is an empty response, not a decision to stop. Nothing you type
will revive it — the session is over, start a new one.

To stay clear of it: never attach `docs/SPEC.md`, do one task per session, and
commit as you go. `docs/BUILD-LOG.md` is what carries state to the next session,
so leaving it accurate is part of finishing.

**Stage explicit paths. Never `git add -A`, `git add .`, or `git commit -a`.**

More than one agent works this tree, sometimes at the same time, and a
blanket stage silently commits whatever the other one has in flight under
your message. That has now happened twice — incident §5.16, and again in
§5.22 where an audit commit about a firewall script swept in an entire
Android screen. Both times the code was fine and the HISTORY was wrong,
which is the expensive half: a commit message that misdescribes its contents
survives every later attempt to understand the change.

    git add path/one path/two     # yes — you named what you meant
    git add -A                    # no

`git status` before you stage, and if you find work you did not do, stop and
report it rather than committing it. This rule is here rather than only in a
paste because the failure mode is two parties who each read different
documents.

## 5. When you hit a decision that is not yours

Do not go silent, and do not stop with nothing delivered.

1. If the task pre-authorises it, take it and record it as a new D-number in
   `docs/BUILD-LOG.md` §2 with the reasoning.
2. Otherwise: finish everything that does not depend on the decision, then state
   the fork plainly — the options, the tradeoff, your recommendation — and stop
   there having banked the rest.

Never weaken a security control to make a test pass. Never add a dependency
without the written justification `CONTRIBUTING.md` standing rule 4 requires
(what it does, who maintains it, its release cadence, what it replaces). If you
are unsure whether something is a security control, assume it is.

## 6. Done means

Tests including at least one negative test; no TODO marking a security control;
nothing logged that derives from a transaction; `make gate` green; and
`docs/BUILD-LOG.md` reconciled against the tree. See `CONTRIBUTING.md` for the
full list.

## 7. Testing rules

- **A negative assertion over generated randomness must pin its input.**
  BIP-39's checksum is 4 bits; a single-word tamper on a random kit
  survives ~1/16 of draws. Hit in §5.6 (mnemonic.rs), hit again in the
  ffi.rs mnemonic golden (D-55 audit). Pin entropy, pin the draw, or test
  the positive on the generated value and the negative on a fixed one.
  The rule scales with survival probability, not with randomness itself:
  it bites where the negative can silently pass draws — weak checksums,
  small moduli, birthday ranges. A cryptographic-property negative over
  128-bit-plus randomness (X25519/AEAD distinctness, tag forgery) fails
  at ~2^-128; generated input there needs no pinning.
