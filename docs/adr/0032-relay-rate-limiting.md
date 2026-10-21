# ADR-0032: Relay rate limiting — two layers, neither of which learns an address

Date: 2026-08-27
Status: Accepted

## Context

Every rate limiter on the relay is keyed on the **identifier** the caller
supplies. `OtpManager` allows five requests per identifier per hour, locks
out after five failed verifications, and backs off exponentially from there.
All of that protects a *named* account well.

None of it engages when the caller names a different identifier every time.
`POST /v1/auth/otp/request` and `POST /v1/auth/otp/verify` are unauthenticated,
and until D-103 they created a tracked record for any string that arrived —
so an attacker naming fresh identifiers was limited by nothing at all, at full
concurrency. (The 250 ms anti-enumeration floor sleeps *after* the handler
returns and holds no lock, so it throttles nothing; it is an equalizer, not a
limiter, and was never meant to be one.)

D-103 bounded the **state** this can create: a length cap, a sweep of inert
records, and a hard ceiling that refuses a new identifier rather than evicting
an existing one. That answers "how much anonymous state can exist", which has
a right answer. It does not answer "how fast can it be created" — an attacker
can still refill a swept table as quickly as it drains — and that question
does not have a right answer, only a trade.

The trade is sharp here because of what the project has already promised.
ADR-0021 puts the operator — this project — at **A3** in its own threat model,
and `THREAT_MODEL.md` publishes **"No IP logging"** unqualified. The deployment
takes that seriously enough that `docs/DEPLOY-ORACLE.md` §6 discards Caddy's
*default* logger as well as the site's, after internet scanners were observed
being logged with full `remote_ip` against the bare IP. The promise is
load-bearing and empirically verified at deploy time.

Every conventional per-client rate limiter wants a client key, and the only
key available is the source address.

## What "No IP logging" actually forbids

Precision matters, because the loose reading forbids rate limiting entirely
and the loose reading is wrong.

The address is **unavoidably present in transit**: the kernel reads it off
every packet, and Caddy reads it to answer. No hosted service can avoid that,
and the promise has never claimed otherwise. What the promise forbids is
**persistence and correlation** — writing the address down, keeping it, or
associating it with an account.

So the test for any proposal is: does it *retain* the address, and can it
*correlate* the address to an identity? Not: does the address exist anywhere
in the machine.

## Decision

Two layers. Neither retains an address, and neither is in the relay.

### Layer 1 — kernel connection-rate limit (`iptables -m hashlimit`)

New TCP connections to 443 are rate limited per source address in the kernel's
own hash table: a fixed-size, in-memory, LRU-evicted structure that is written
nowhere and expires on its own. `deploy/skipti-connection-limit.sh` installs
it, version-controlled rather than living only on the box (D-84's lesson).

This layer is deliberately placed **below** both Caddy and the relay. The
component that already had the address by physical necessity is the one that
uses it, and nothing above ever gains it. `-j LOG` is forbidden in the script
and the script says why: a logging jump would convert this layer from
"compatible with §2.6" to "the thing that broke §2.6".

**Honest limit, stated because it is the reason layer 2 exists:** this counts
*connections*, not requests. HTTP keep-alive carries many requests on one
connection, so a single well-behaved connection can still issue requests as
fast as it likes. Layer 1 blunts naive floods and costs nothing; it does not
bound request volume.

**Second honest limit — shared source addresses.** The budget is per source
address, and carrier-grade NAT puts many unrelated users behind one. At
20/minute a busy CGNAT egress could exceed the budget on legitimate traffic
alone, and every user behind it would be dropped together. That is acceptable
at pre-release scale and is **not** acceptable at scale; the tripwire below
names it. Layer 2 has no equivalent problem, because it has no client key.

**Third, and the one that needed correcting after the first live run:** the
kernel's table is *readable* at `/proc/net/ipt_hashlimit/<name>`, and it lists
the source addresses currently being counted. That does not make this
persistence — the entries are ephemeral kernel memory that expire on their own,
and nothing writes them anywhere — so the §2.6 analysis stands. But "written
nowhere" was too comfortable a phrase: there is a readable view, and anything
that `cat`s it to a terminal or scrapes it into a monitoring pipeline turns an
expiring counter into a log of client addresses. `verify` therefore does not
print it, and says in-line why not.

### Layer 2 — a global admission bucket in the relay, with no client key

An integer token bucket gates the admission of identifiers `OtpManager` has
**never seen**: `OTP_ADMIT_PER_SEC` = 10, `OTP_ADMIT_BURST` = 60.

The property that makes this the right shape is that it takes **no client key
of any kind**. There is nothing to hash, salt, retain, or leak, so it cannot
weaken §2.6 even in principle — which is exactly what is wanted in the one
component ADR-0021 designates the adversary.

It is charged only where new state is about to be created, and only after the
D-103 cap has already allowed it. An identifier already tracked — every user
with a live code or an active lockout — never touches the bucket.

## Options refused, and why

1. **A per-address limiter inside the relay** (a token bucket keyed on a
   salted, short-lived, never-persisted hash of the source address).
   Technically defensible: a rotating salt with no persistence is arguably
   within the promise. Refused anyway, on the grounds that make ADR-0021
   coherent — it would put the client address **into the component the threat
   model treats as the adversary**, and require the relay to trust a
   `X-Forwarded-For` header to get it. "Arguably within the promise" is not
   the standard this project holds §2.6 to, and layer 1 already places that
   capability where it does no such damage.

2. **Cloudflare proxying (orange cloud).** Refused. `DEPLOY-ORACLE.md` §5
   records DNS-only as a deliberate choice: proxying terminates TLS and makes
   Cloudflare a party that sees connection metadata for every user. Reversing
   a recorded decision to buy rate limiting is the wrong trade, and the doc
   already says amending §2.6 would be the precondition.

3. **A custom Caddy build carrying the `caddy-ratelimit` plugin.** Refused.
   It is not in the standard distribution, so this means `xcaddy`, a
   self-built binary, and the loss of the apt upgrade path for the component
   that terminates TLS. That is a real operational regression — an unpatched
   TLS terminator is a worse outcome than an unthrottled OTP endpoint — for a
   capability the two layers above already provide.

4. **Proof-of-work on `otp/request`.** Refused. It needs no address, which is
   attractive, but it taxes the slowest legitimate device hardest, adds a
   client-side dependency to every shell, and buys little against an attacker
   with rented CPU.

## Consequences

- A flood **degrades new sign-ins** while it lasts. It does not affect anyone
  already tracked, and it cannot evict them. The failure mode is "the queue
  is long", not "your account stopped working", and that ordering is the whole
  point of charging the bucket only for new state.
- Layer 1 needs a box command the operator runs; layer 2 ships in the binary.
  The relay is correct on its own, before layer 1 is installed.

### What layer 2 does and does not leak

Worth writing out, because a saturation-conditional refusal is exactly the
shape an enumeration oracle takes and the analysis should not be re-derived.

During saturation a never-seen identifier is refused while a tracked one is
served. That distinguishes **"has this identifier requested a code recently"**
— not "does this account exist". The relay's OTP path never consults the
registry: `subject_for` is created on the first *successful verify*, so
`issue` treats real and invented identifiers identically, by design (SPEC
§4.3, D-27). A real user who has not signed in this hour is untracked exactly
like a fabricated address.

The residual signal is therefore about recent activity, and it is only
readable during a saturation the attacker caused. The existing five-per-hour
limiter already exposes the same class of signal to anyone who probes. No new
existence oracle is created.

## Tripwires

Reopen this ADR before shipping if any of these becomes true:

- Any layer writes a client address anywhere, including a rate-limit log line
  or a metrics label with an address in it.
- Anything reads `/proc/net/ipt_hashlimit/*` other than a human debugging
  live, once. Scraping it on a timer, shipping it to monitoring, or printing
  it from a script all convert an expiring kernel counter into exactly the log
  §2.6 forbids.
- Real users report being rate limited. The per-source budget is shared by
  everyone behind a NAT, so the first symptom of the CGNAT limit above is a
  support report, not a metric — there is deliberately no metric that would
  show it, because the metric would need the addresses. Revisit the layering
  rather than raising the number blindly.
- The relay gains any per-address or per-connection input, `X-Forwarded-For`
  included.
- Cloudflare proxying is turned on for any reason — that changes §2.6's party
  list and must amend the published table first.
- The relay's OTP path ever consults the registry before issuing, which would
  turn the saturation analysis above from "recent activity" into a genuine
  existence oracle.
