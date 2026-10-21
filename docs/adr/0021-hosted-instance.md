# ADR-0021: SKIPTI runs a hosted instance

Status: Accepted (settles SPEC §17.5 against its recommendation)

## Context

§17.5 recommended self-host plus cloud-drive, because hosting makes you the
A3 adversary in your own threat model and puts an abuse and subpoena surface
under whatever entity ships it. That recommendation is sound on its own
terms and is being declined anyway.

The founder's reasoning: SKIPTI is for ordinary people, starting with
friends. Self-hosting excludes exactly them — nobody's friends will run
`docker compose up` to split a dinner bill — and the cloud-drive backend
that §17.5 leaned on does not exist yet (ADR-0003 build order) and is the
fiddliest to build across three platforms. A hosted instance is the only
path where "install the app, sign in, done" is true at first contact.

The infrastructure choice is deliberate: Oracle Cloud Always Free resources
on a pay-as-you-go account. PAYG tenancies keep Always Free resources from
being reclaimed, which matters because a reclaimed instance destroys every
account it hosts (see Consequences).

## Decision

SKIPTI operates a hosted instance, run by the founder personally, on Oracle
Cloud Always Free resources under a pay-as-you-go account. This settles
SPEC §17.5 in the opposite direction from its recommendation. Two
consequences are binding, not implied:

**(a) The threat model changes shape.** THREAT_MODEL.md is amended in this
commit: the hosted instance means the project itself occupies the A3 role
("malicious sync operator") for hosted users — the mitigations stay
cryptographic (the server holds ciphertext and cannot read content), but
operational trust now substitutes for "run by nobody," and that trade must
be visible to users rather than buried. §2.6's metadata table states "No IP
logging." With a real deployment, that sentence becomes an operational
commitment, and the defaults are hostile to it: Oracle's load balancer and
nginx both log IPs out of the box. Disabling and verifying IP logging across
LB → reverse proxy → application logs is a **deployment requirement that
must be verified before any real user connects**, not an aspiration.

**(b) D-19 stops being deferrable.** The server currently holds all state —
accounts, sessions, passkey credentials, group envelopes, escrow blobs — in
six in-memory mutexes. A restart destroys all of it. For a self-hosted
hobby relay that was an honest deferral; for a hosted instance holding
friends' escrow blobs it is not: **a friend whose escrow blob dies loses
their Master Key recovery path.** Persistent server storage is therefore a
hard precondition of any real user connecting to the instance — a blocking
item ahead of everything else in Phase 8's deployment story. It is recorded
here as such; implementation is deliberately not part of this commit.

## Consequences

- Friends can use SKIPTI without becoming operators; the "install and sign
  in" path exists from day one of the hosted instance.
- The founder personally carries the A3 role for hosted users: patching,
  backups, subpoena response, and abuse handling are their responsibilities,
  backed by published costs if the instance ever grows past what donations
  cover (SPEC §17.5's own condition, carried over).
- Capacity is bounded by Always Free limits; exceeding them forces either a
  paid tier or a revisit of this decision — recorded in BUILD-LOG §4 with
  explicit trigger conditions.
- Persistent server storage (D-19's deferral) is now the top blocker for
  anyone connecting; the authz gap (§4.4, closed in design by ADR-0022)
  must land alongside it, since multi-user persistence without ownership
  rules would be worse than no persistence.
- Self-hosting remains fully supported and unchanged; the docker compose
  path stays the trust-minimising option stated in THREAT_MODEL.md.
