# Deploying the SKIPTI relay on Oracle Cloud

Runbook for the instance ADR-0021 chose: Oracle Cloud Always Free on a
pay-as-you-go account, serving `skipti.vardrlabs.com` (ADR-0027).

Read ADR-0021 and ADR-0027 first. Two things there are operational
commitments, not background: `THREAT_MODEL.md` §2.6 publishes **"No IP
logging"**, and the WebAuthn relying-party ID is **permanent** — every passkey
registered against it dies if it changes.

---

## 0. Before you start

| | |
|---|---|
| Oracle account | pay-as-you-go (Always Free resources are not reclaimed on PAYG) |
| Domain | `vardrlabs.com` on Cloudflare, with `skipti` free |
| Local | this repo, Rust toolchain, an SSH keypair |

### SSH key

Generate your own:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/skipti_oracle -C "skipti-relay"
chmod 600 ~/.ssh/skipti_oracle
```

Keep `~/.ssh/skipti_oracle` private; you upload only `skipti_oracle.pub`.

The instance-creation screen also offers to generate a keypair for you and let
you download both halves. That works, and it is a defensible choice — but it
is not less work. Either way you end up with a private key file on your Mac
that you pass to `ssh -i`; the console path just adds a download and a
`chmod 600`. Generating locally additionally guarantees the private key never
existed anywhere but your machine, which the console path cannot promise in
the same way.

Proportion, so this is not overstated: if that key leaked, the attacker gets
the box and `relay-vault.db`, which holds every escrow blob. A-20 proves an
exfiltrated escrow blob opens for no wrong passphrase, so even full compromise
yields no user's Master Key. The exposure is the A3 adversary THREAT_MODEL
already describes — altering or dropping ciphertext, and seeing metadata.

If you already downloaded Oracle's keypair, use it and rotate when convenient:

```bash
# on the instance, as ubuntu
echo "<your-new-public-key>" >> ~/.ssh/authorized_keys
# verify the new key works in a SECOND terminal before removing the old line
```

Never remove the old key until the new one is proven — locking yourself out of
an Oracle instance means rebuilding it.

---

## 1. Pick a region

Choose **once** — Always Free resources are pinned to your home region and
moving later means rebuilding. Pick the region closest to your users.

ARM (Ampere A1) capacity is frequently exhausted in popular regions. If
instance creation fails with "Out of host capacity", that is normal and not
something you did wrong: retry later, or pick a neighbouring region **before**
you have anything deployed.

---

## 2. Network — VCN, gateway, routing

Use the wizard rather than building by hand. In the console:

**Networking → Virtual Cloud Networks → Create VCN with Internet Connectivity**

That single wizard creates everything you need and wires it together:

- a VCN (default CIDR `10.0.0.0/16` is fine)
- a **public subnet** (`10.0.0.0/24`) — the instance lives here
- a private subnet — unused, harmless
- an **Internet Gateway** — the thing that gives the instance a route out
- a **route table** entry sending `0.0.0.0/0` to that gateway
- a default **security list**

Name it `skipti-vcn`. Everything else can stay default.

> Building this by hand is where people lose an afternoon: a VCN with no
> Internet Gateway, or a gateway with no `0.0.0.0/0` route pointing at it,
> produces an instance that boots fine and is unreachable, with no error
> anywhere.

### Open 80 and 443 in the security list

The wizard's security list allows SSH (22) only.

**Networking → VCN → `skipti-vcn` → Security Lists → Default Security List →
Add Ingress Rules**

Add two, both stateless **off** (i.e. stateful):

| Source CIDR | Protocol | Destination port |
|---|---|---|
| `0.0.0.0/0` | TCP | 80 |
| `0.0.0.0/0` | TCP | 443 |

Leave 8080 **closed**. The relay binds `0.0.0.0:8080`, but only the reverse
proxy on the box itself will talk to it. Nothing external should reach 8080
directly — it is plain HTTP.

---

## 3. Create the instance

**Compute → Instances → Create Instance**

- **Image**: Canonical Ubuntu — the **Minimal aarch64** build. Minimal is the
  right call: fewer packages, smaller attack surface, and you are installing
  what you need anyway.
- **Shape**: `VM.Standard.A1.Flex` (Ampere ARM).
- **OCPU / memory**: Oracle's current Always Free ARM allocation is smaller
  than it once was — take what the console offers you as free (commonly
  **2 OCPU / 12 GB** now, previously 4/24). The relay is comfortable well
  below that; do not exceed the free allocation or you start paying.
- **Boot volume**: default (~47 GB) is ample.
- **SSH key**: paste the contents of `~/.ssh/skipti_oracle.pub`.
- **Networking**: the VCN and **public subnet** from step 2, and make sure
  **Assign a public IPv4 address** is on.

Create it, and note the **public IP** once it is running.

---

## 4. First connection, and the firewall that catches everyone

```bash
ssh -i ~/.ssh/skipti_oracle ubuntu@<PUBLIC_IP>
```

Oracle's Ubuntu images ship host firewall rules that block everything except
SSH — **independently of your security list**. Opening 80/443 in the console
is necessary and not sufficient. On the box:

First find where the catch-all REJECT sits — the position differs between
images, and a rule inserted below it does nothing:

```bash
sudo iptables -L INPUT -n --line-numbers | head -12
```

Look for `REJECT ... reject-with icmp-host-prohibited` and note its line
number. Insert the ACCEPT rules **at that number**, so they land above it:

```bash
REJ=$(sudo iptables -L INPUT -n --line-numbers | awk '/REJECT/{print $1; exit}')
sudo iptables -I INPUT "$REJ" -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo iptables -I INPUT "$REJ" -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo netfilter-persistent save
```

Verify the order actually changed — this is the check, not the insert:

```bash
sudo iptables -L INPUT -n --line-numbers | head -10
```

`dpt:80` and `dpt:443` must appear ABOVE the REJECT line. iptables stops at
the first match, so rules below it are never evaluated: Caddy runs, listens on
both ports, and still never sees a packet. Observed live on this project's own
instance, where the REJECT was at position 5 while an earlier version of this
runbook said to insert at 6.

If `netfilter-persistent` is missing on the minimal image:

```bash
sudo apt update && sudo apt install -y iptables-persistent
```

This is the single most common reason a correctly-configured Oracle instance
appears dead on 80/443.

### 4.1 Connection rate limit (ADR-0032 layer 1)

The relay's own limiters are all keyed on the identifier a caller supplies, so
a caller naming a fresh identifier every time is limited by none of them. The
relay bounds that in its own way (a global admission bucket with no client
key); this is the layer below it.

Do not hand-write the rule. `deploy/skipti-connection-limit.sh` carries it,
because the constraint that matters is one a copied command loses: **these
rules must never carry `-j LOG`**. A logging jump turns the layer that is
compatible with THREAT_MODEL's published "No IP logging" into the thing that
makes it false. The script installs the rule, refuses to drift between apply
and remove, and its `verify` FAILS if any `LOG` target exists in the chain.

**On the instance**, not on your workstation — this is `iptables`, so it is
Linux-only and there is nothing for it to do on a Mac. From a repo checkout on
the box (`scp deploy/` over if there isn't one, exactly as §10.2 says for the
backup units):

```bash
ssh -i ~/.ssh/skipti_oracle ubuntu@<PUBLIC_IP>
cd ~/Skipti          # or wherever deploy/ landed
sudo ./deploy/skipti-connection-limit.sh apply
sudo ./deploy/skipti-connection-limit.sh verify
```

The rule touches port 443 only, so **it cannot lock you out of SSH** — port 22
is untouched and this is `--state NEW` on 443 alone.

`verify` must print the rule ABOVE the `dpt:443` ACCEPT — same first-match
rule as above; installed below the ACCEPT it would look present and never be
reached — and `ok: 0 LOG targets in INPUT`. Then persist it, exactly like the
ACCEPT rules:

```bash
sudo netfilter-persistent save
```

Confirm it survives a reboot, because an unpersisted rule silently vanishes
and the first anyone knows is the next flood:

```bash
sudo reboot
# then, once back:
sudo ./deploy/skipti-connection-limit.sh verify
```

To roll it back: `sudo ./deploy/skipti-connection-limit.sh remove`, then
`sudo netfilter-persistent save` again.

Then bring the system up to date:

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y ca-certificates curl
```

---

## 5. DNS — Cloudflare

In the Cloudflare dashboard for `vardrlabs.com`:

- **Type** `A`, **Name** `skipti`, **IPv4** `<PUBLIC_IP>`
- **Proxy status: DNS only (grey cloud)** — not proxied

The grey cloud is deliberate and is a threat-model decision. If Cloudflare
proxies, it terminates TLS and becomes a party that sees connection metadata —
IPs, timing, request sizes — which is exactly the list §2.6 publishes, and
§2.6 promises no IP logging. Payloads stay E2EE ciphertext either way, so this
is about metadata, not contents. Proxying is defensible, but only if you amend
§2.6 first to say Cloudflare sees it too.

Verify before continuing — TLS issuance depends on it:

```bash
dig +short skipti.vardrlabs.com     # must print your instance IP
```

Nothing on `vardrlabs.com` itself changes; the Workers site keeps serving the
apex and `www` untouched.

---

## 6. TLS

Caddy, because it obtains and renews certificates automatically and its
config is three lines. On the instance:

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy
```

Replace `/etc/caddy/Caddyfile` with:

```
# THREAT_MODEL §2.6 publishes "No IP logging" — unqualified. Suppressing only
# the site's log is NOT enough: Caddy's DEFAULT logger handles everything that
# matches no site block, and internet scanners hit the bare IP within minutes
# of a host coming up. Observed live on this instance: CensysInspect and Palo
# Alto scanners logged with full remote_ip while the site block was silent.
# The global block below is what makes the published promise actually true.
{
	log {
		output discard
	}
}

skipti.vardrlabs.com {
	log {
		output discard
	}
	reverse_proxy 127.0.0.1:8080
}
```

Validate before reloading — a broken Caddyfile takes TLS down with it:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

It should print `Valid configuration` and, tellingly, `redirected default
logger ... to discard`. Then verify the promise empirically rather than
trusting the config — hit the bare IP, not just the hostname:

```bash
curl -s -o /dev/null http://<PUBLIC_IP>/probe
sudo journalctl -u caddy --since '60 sec ago' --no-pager \
  | grep 'http.log.access' | grep -c remote_ip     # must be 0
```

Filter on `http.log.access` specifically: Caddy's `admin.api` logger records
`remote_ip: 127.0.0.1` for its own reload traffic over the local admin socket,
which is not a client IP and will otherwise make a correct config look broken.

```bash
sudo systemctl reload caddy
```

Caddy will obtain a Let's Encrypt certificate on first request. That requires
step 5's DNS to already resolve and port 80 to be genuinely reachable.

> The `log { output discard }` block is load-bearing, not tidiness. Leaving
> Caddy's default access log on would make §2.6's published promise false.

---

## 7. Build and install the relay

**When to do this:** after step 6's certificate is genuinely valid, and not
before. The relay is only useful once TLS terminates in front of it, and D-57
records passkeys as unverifiable until TLS plus the step 8 AASA file exist —
so this step is what unblocks the largest untested surface in the project.
Building it against a broken certificate means testing it twice.

### Build on the instance, not cross-compiled

Cross-compiling macOS → aarch64 Linux needs a toolchain that is not installed
by default (no target, no linker, no `cross`, no Docker), and SQLCipher's
bundled C is precisely the part that makes cross-compilation fragile — the
same C that broke the first iOS build. A native build on the box avoids all of
it. 2 OCPU compiles this workspace slowly but without drama.

**On your Mac** — export a clean source tree. `git archive` takes exactly what
is committed, so no local scratch files or credentials travel:

```bash
cd ~/Documents/Skipti
git rev-parse HEAD > /tmp/skipti-commit.txt
git archive --format=tar.gz -o /tmp/skipti-src.tar.gz HEAD
scp /tmp/skipti-src.tar.gz /tmp/skipti-commit.txt ubuntu@<PUBLIC_IP>:/tmp/
```

`skipti-commit.txt` carries the full 40-character commit the tarball was cut
from. The instance has no git checkout, so it cannot work this out itself
(D-137: this runbook used to run `git rev-parse HEAD` on the instance).

This deliberately puts no GitHub credentials on the server. Cloning the
private repo from the instance would mean a deploy key or token living on a
machine that faces the internet; a tarball does not.

**On the instance** — toolchain, then build:

```bash
sudo apt update
sudo apt install -y build-essential pkg-config libssl-dev clang
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
source "$HOME/.cargo/env"
```

```bash
# SKIPTI_GIT_COMMIT is the deploy's verifiable identity (ADR-0030's
# companion; the endpoint is GET /version). The tarball carries no .git,
# so the commit MUST be passed from the checkout it was cut from, which is
# the file copied over with it:
SKIPTI_GIT_COMMIT=$(cat /tmp/skipti-commit.txt)
echo "building commit: $SKIPTI_GIT_COMMIT"
rm -rf ~/skipti && mkdir -p ~/skipti && tar -xzf /tmp/skipti-src.tar.gz -C ~/skipti
cd ~/skipti && SKIPTI_GIT_COMMIT="$SKIPTI_GIT_COMMIT" \
  cargo build --release -p skipti-server
```

The first deploy answered `{"commit":"unknown","version":"0.1.0"}` — `build.rs`
had no `.git` to read and refused to invent a hash, which was honest but made
the deploy unverifiable. `build.rs` now accepts the commit from the
environment first (validated as 40 hex; a malformed value is rejected with a
visible `cargo:warning`, never embedded), which is why the build command above
passes `SKIPTI_GIT_COMMIT`. After this deploy, verify from anywhere:

```bash
curl -s https://skipti.vardrlabs.com/version
# on the Mac: the "commit" value must equal
cat /tmp/skipti-commit.txt
```

`"unknown"` in a response means the build had no commit context — a deploy
that skipped the env var, not a broken endpoint.

Expect this to take a while on 2 OCPU — SQLCipher and the crypto crates
dominate. It should end with `Finished \`release\` profile`.

### Install

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin skipti
sudo mkdir -p /var/lib/skipti
sudo chown skipti:skipti /var/lib/skipti
sudo chmod 700 /var/lib/skipti
sudo install -m 755 ~/skipti/target/release/skipti-server /usr/local/bin/skipti-server
```

`/var/lib/skipti` is `700` and owned by `skipti` on purpose: it holds every
account's escrow blob. Nothing else on the box should be able to read it.

### The service

`/etc/systemd/system/skipti-relay.service`:

```ini
[Unit]
Description=SKIPTI relay
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=skipti
Group=skipti
ExecStart=/usr/local/bin/skipti-server
Restart=always
RestartSec=5

Environment=SKIPTI_RELAY_DB=/var/lib/skipti/relay-vault.db
Environment=SKIPTI_RP_ID=skipti.vardrlabs.com
Environment=SKIPTI_ORIGIN=https://skipti.vardrlabs.com
Environment=SKIPTI_APPLE_AUDIENCE=com.vardrlabs.skipti

# Sign in with Google (ADR-0035, D-130). OAuth client ids from Google Cloud —
# public identifiers, not secrets (they ship inside the apps). While one is
# unset, that half of Google sign-in is closed; with both unset,
# POST /v1/auth/google/start refuses. Set on production 2026-09-18.
Environment=SKIPTI_GOOGLE_WEB_CLIENT_ID=403030719865-7bic8ot5edm533n9gi3j8hthiv0c6bmt.apps.googleusercontent.com
Environment=SKIPTI_GOOGLE_IOS_CLIENT_ID=403030719865-pmgfvj4eo4l73bao8lqblqmpera4augj.apps.googleusercontent.com

# SKIPTI_DEV_UNSAFE_SESSION_MINT is deliberately ABSENT. Setting it to 1
# registers POST /v1/dev/session, which mints a session for any identifier
# with no credential proof. Never set it here.

NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=/var/lib/skipti

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now skipti-relay
sudo systemctl status skipti-relay
```

**The six environment variables above are the entire production
configuration.** `SKIPTI_RP_ID` and `SKIPTI_ORIGIN` default to `localhost`,
which silently produces a relay that cannot serve real passkeys — every
credential would bind to the wrong relying party. A unit file written before
2026-09-18 has no Google lines at all: ADD the two `SKIPTI_GOOGLE_…` lines
under `SKIPTI_APPLE_AUDIENCE`, there is nothing to replace.

### 7.1 Updating a running relay

**One command (from D-159 on):** on the Mac, from a clean checkout of the
commit you mean to deploy, run `ssh-add ~/.ssh/skipti_oracle` once, then
`deploy/ship-relay.sh ubuntu@<PUBLIC_IP>`. It does steps 1 to 5 below
(backup named after the live commit), installs the `/join` page, and
checks `/version`. The steps are kept here for reference and for rollback.

Used for the 2026-09-18 upgrade (69aeb87 → 9952f95). Build first, so the old
relay keeps serving through the slow part; the downtime is only the
stop → install → start at the end.

1. **On the Mac:** the three tarball commands at the top of step 7 (commit
   file, `git archive`, `scp`).
2. **On the instance:** the build block above. Wait for
   `Finished \`release\` profile`. If it says the compiler is too old,
   `rustup update stable` and build again.
3. **If the service file changed** (new `Environment=` lines, say):
   `sudo nano /etc/systemd/system/skipti-relay.service`.
4. **Stop, back up, install, start.** Name the backup directory after the
   commit you are REPLACING (here `69aeb87`):

```bash
sudo systemctl stop skipti-relay
sudo mkdir -p /var/lib/skipti/backup-OLDCOMMIT
sudo sh -c 'cp -p /var/lib/skipti/relay-vault.db* /var/lib/skipti/backup-OLDCOMMIT/'
sudo ls -l /var/lib/skipti/backup-OLDCOMMIT/
sudo install -m 755 ~/skipti/target/release/skipti-server /usr/local/bin/skipti-server
sudo systemctl daemon-reload
sudo systemctl start skipti-relay
sudo systemctl status skipti-relay --no-pager
```

   Two things here are deliberate:
   - **`sudo sh -c '…'` around the copy.** `/var/lib/skipti` is `700` and
     owned by `skipti`, so in a plain `sudo cp /var/lib/skipti/relay-vault.db* …`
     the `*` is expanded by YOUR shell, which cannot list that directory; the
     pattern stays literal and `cp` fails with "No such file or directory"
     (this runbook's mistake, hit on 2026-09-18). Inside `sh -c` the root
     shell expands it.
   - **`cp` is safe here only because the relay is STOPPED** and every file
     (`.db`, `-wal`, `-shm`) is copied together. On a live database use
     `sqlite3 .backup` (§10.1), never `cp`.
5. **Verify:** step 9, and `/version` must name the commit in
   `skipti-commit.txt`.

**Schema migrations are one-way.** A new relay may migrate the database on
first start (9952f95 took it from schema v2 to v3, 03e6abf from v3 to v4, and
D-156's relay takes it from v4 to v5: two new tables, `r_invites` and
`r_inbox`, and no change to an existing row), and the old binary cannot
open the result. So rolling back means the OLD binary plus the backup from
step 4, never the old binary alone:

```bash
sudo systemctl stop skipti-relay
sudo sh -c 'cp -p /var/lib/skipti/backup-OLDCOMMIT/relay-vault.db* /var/lib/skipti/'
sudo chown skipti:skipti /var/lib/skipti/relay-vault.db*
# then install the old binary (rebuild it from its own tarball) and start
```

After a schema migration, run the restore drill (§10.4) once the backup
timer has taken a snapshot of the new schema.

---

## 8. Passkeys need an AASA file

iOS will not perform a passkey ceremony without an Apple App Site Association
file served from the RP ID over HTTPS.

On the instance:

```bash
sudo mkdir -p /var/www/skipti/.well-known
```

`/var/www/skipti/.well-known/apple-app-site-association` — **no file
extension**, served as `application/json`:

```json
{
  "webcredentials": {
    "apps": ["27GK2T7UCT.com.vardrlabs.skipti"]
  }
}
```

`27GK2T7UCT` is Vardr Labs LLC's Apple Team ID. Deployed and verified live:
`HTTP/2 200`, `content-type: application/json`, served from the RP ID.

Match it **before** the reverse_proxy in a `handle` block, and wrap the proxy
in its own `handle`, or the relay swallows the path:

```
	handle /.well-known/apple-app-site-association {
		header Content-Type application/json
		root * /var/www/skipti
		file_server
	}

	handle {
		reverse_proxy 127.0.0.1:8080
	}
``` Add to the Caddyfile,
before the `reverse_proxy` line:

```
	handle /.well-known/apple-app-site-association {
		header Content-Type application/json
		root * /var/www/skipti
		file_server
	}
```

The iOS app also needs the matching `webcredentials:skipti.vardrlabs.com`
associated-domains entitlement — that half is app-side, recorded against D-57.

### 8.1 Android needs an assetlinks.json (the same story, the other platform)

Credential Manager will not perform a passkey ceremony without a Digital
Asset Links statement served from the RP ID, proving the app may hold
credentials for this host. Verified live 2026-08-29: `HTTP/2 200`,
`content-type: application/json`, and **accepted by Google's own validator
with two statements and zero errors** — which is the check that matters, not
that the file parses:

```bash
curl -sS -G 'https://digitalassetlinks.googleapis.com/v1/statements:list' \
  --data-urlencode 'source.web.site=https://skipti.vardrlabs.com' \
  --data-urlencode 'relation=delegate_permission/common.get_login_creds'
```

**The ordering is a chain, not a checklist**, and it has TWO keys in it, not
one. Play App Signing (mandatory for new apps) splits them:

- the **upload key** — `~/skipti-upload.jks`, yours, which signs the AAB you
  upload and signs every `direct`-channel build;
- the **app signing key** — Google's, generated at enrolment, which re-signs
  the AAB before any user installs it from Play.

**The app a Play user installs is signed by the APP SIGNING key, so its
SHA-256 is the one Credential Manager checks.** A file carrying only the
upload fingerprint authorises your `direct` builds and your own local
installs and then fails on exactly the users you shipped to. That is worth
stating plainly because of how it presents: not as a broken deploy, but as
"passkeys work on my phone and not for testers".

So the chain is: upload keystore → upload fingerprint → assetlinks.json
(`direct` works, Play does not yet) → first AAB upload → Google generates the
app signing key → **add its fingerprint too** → Play works. The last two
steps cannot happen earlier: the app signing key does not exist until the
first upload.

Neither fingerprint is written into this document. Both are public (they are
served at a public URL), but embedding either would make the runbook silently
wrong the day a key is rotated — the same reason `<PUBLIC_IP>` is a
placeholder throughout.

Read the UPLOAD fingerprint **on the Mac**, from the keystore:

```bash
keytool -list -v -keystore ~/skipti-upload.jks -alias skipti-upload | grep 'SHA256:'
```

Read the APP SIGNING fingerprint from **Play Console**, after the first
upload: **Protected with Play → Play Store distribution → Go to Play app
signing** (the path Google's help gives as of 2026-09; the older Test and
release → App integrity menu now redirects to Protected with Play). That page shows
both certificates; the one under "App signing key certificate" is what this
file is missing until you add it. If both pages ever show the same value, you
supplied your own app signing key and one entry suffices — but the default,
and the recommendation, is to let Google generate it and list both.

Then, **on the instance**, build the JSON from shell variables rather than
hand-editing it — a mistyped fingerprint fails in a way that looks like a
Caddy problem:

```bash
UPLOAD_FP="<the upload keystore's SHA256, colons included>"
PLAY_FP="<Play Console's app signing SHA256; omit the second entry until you have it>"
sudo mkdir -p /var/www/skipti/.well-known
printf '[{"relation":["delegate_permission/common.get_login_creds"],"target":{"namespace":"android_app","package_name":"com.vardrlabs.skipti","sha256_cert_fingerprints":["%s","%s"]}},{"relation":["delegate_permission/common.get_login_creds"],"target":{"namespace":"android_app","package_name":"com.vardrlabs.skipti.direct","sha256_cert_fingerprints":["%s"]}}]\n' "$UPLOAD_FP" "$PLAY_FP" "$UPLOAD_FP" \
  | sudo tee /var/www/skipti/.well-known/assetlinks.json >/dev/null
```

`sha256_cert_fingerprints` is an ARRAY, and listing both on the play package
is the whole point: one package name is legitimately signed two ways over its
life — your upload key for a locally installed release build, Google's app
signing key for the Play install — and both must be authorised.

**Both** application ids are authorised: ADR-0010 makes `direct` a real
distribution channel, not a test artifact. `direct` carries the upload
fingerprint ONLY — it never passes through Play App Signing, so Google's key
never signs it.

The Caddy block goes beside the AASA one and, critically, **above** the
catch-all `handle { reverse_proxy … }`:

```
	handle /.well-known/assetlinks.json {
		header Content-Type application/json
		root * /var/www/skipti
		file_server
	}
```

Below the proxy it is never reached and the relay answers instead — which
presents as **401**, not 404, because unmatched relay routes fall through the
bearer gate (incident §5.23). A 401 on this path means the block is in the
wrong place, not that anything is broken.

Validate before reloading — a broken Caddyfile takes TLS down with it:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl reload caddy
```

What this does NOT unblock: the ceremony still needs a real device with a
screen lock and a credential provider. An emulator without one answers
`CreateCredentialNoCreateOptionException`, which is correct behaviour and not
a defect (D-118).

### 8.2 Invite links (D-159): app links and the /join page

An invite link is `https://skipti.vardrlabs.com/join#<secret>`. The phone
opens it in SKIPTI only when this domain says the app may handle `/join`; until
then it opens in the browser, which shows the page below, and the person pastes
the link into SKIPTI → Groups → Join a group. Both apps already declare the
link (`applinks:skipti.vardrlabs.com` on iOS; an `autoVerify` intent filter on
Android). Three changes on the instance, then validate and reload Caddy.

**1. AASA: add `applinks`** (keep `webcredentials` exactly as it is):

```bash
printf '{"applinks":{"details":[{"appIDs":["27GK2T7UCT.com.vardrlabs.skipti"],"components":[{"/":"/join","comment":"invite links (D-159)"}]}]},"webcredentials":{"apps":["27GK2T7UCT.com.vardrlabs.skipti"]}}\n' \
  | sudo tee /var/www/skipti/.well-known/apple-app-site-association >/dev/null
```

**2. assetlinks: add `handle_all_urls`** to both statements, with the same
fingerprints as §8.1 (the relation array gains one entry; nothing else moves):

```bash
UPLOAD_FP="<the upload keystore's SHA256, colons included>"
PLAY_FP="<Play Console's app signing SHA256>"
printf '[{"relation":["delegate_permission/common.get_login_creds","delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"com.vardrlabs.skipti","sha256_cert_fingerprints":["%s","%s"]}},{"relation":["delegate_permission/common.get_login_creds","delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"com.vardrlabs.skipti.direct","sha256_cert_fingerprints":["%s"]}}]\n' "$UPLOAD_FP" "$PLAY_FP" "$UPLOAD_FP" \
  | sudo tee /var/www/skipti/.well-known/assetlinks.json >/dev/null
```

**3. The /join page** (`firebase/public/join/index.html` in the repo since D-199; copy it over with
the release tarball, or scp it on its own). It loads nothing from anywhere,
and the invite secret stays in the fragment, which browsers never send:

```bash
sudo mkdir -p /var/www/skipti/join
sudo install -m 644 ~/skipti/firebase/public/join/index.html /var/www/skipti/join/index.html
```

Caddy, beside the other `handle` blocks and **above** the catch-all proxy:

```
	handle /join {
		root * /var/www/skipti
		rewrite * /join/index.html
		header Content-Security-Policy "default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-EFFNKyFe2EdoRLOiKfYH+4N8e7wmD/iLcH9toZ+3NK0='; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
		header Referrer-Policy no-referrer
		file_server
	}
```

The script hash pins the page's one small script; edit the page and the hash
must be recomputed (`scripts/` has no tool for it yet: the SHA-256 of the
script's text, base64).

Check: `curl -sI https://skipti.vardrlabs.com/join` answers 200 with that CSP;
Apple's CDN refreshes the AASA within a day (a fresh app install re-reads it);
Google's validator (§8.1's command with
`relation=delegate_permission/common.handle_all_urls`) lists both packages.

---

## 9. Verify

```bash
curl -sI https://skipti.vardrlabs.com/ | head -3        # 200, valid TLS
curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST https://skipti.vardrlabs.com/v1/dev/session   # MUST be 401
curl -s https://skipti.vardrlabs.com/.well-known/apple-app-site-association
```

The middle check is the important one. `401` means the dev mint is absent, as
it must be. Anything else means `SKIPTI_DEV_UNSAFE_SESSION_MINT` leaked into
the environment and the relay will hand a session to anyone who asks.

Verify WHICH commit is live — `/version` names the commit the binary was
built from (step 7 passes `SKIPTI_GIT_COMMIT` at build time), so a deploy is
checkable against the exact commit you intended:

```bash
curl -s https://skipti.vardrlabs.com/version
# compare the "commit" value against the commit you MEANT to deploy:
git rev-parse <intended-commit>
```

A mismatch is "the deploy did not take" — treat it as a failed deploy and
re-run step 7, never as a versioning oddity to assume away. If the answer is
`"unknown"`, the build was made without `SKIPTI_GIT_COMMIT` (step 7 skipped)
and the deploy is unverifiable by definition: redeploy properly.

Then confirm the promise you published:

```bash
sudo journalctl -u caddy --since '5 min ago' | grep -c '"remote_ip"'
```

Zero. If it is not zero, §2.6 is false and the log config in step 6 did not
take effect.

---

## 10. Backups (ADR-0030 — do this before ANY real user exists)

`/var/lib/skipti/relay-vault.db` holds every account's escrow blob. Losing it
means users lose their Master Key recovery path — the one thing SKIPTI cannot
rebuild for them. The policy is ADR-0030: TWO layers, both inside the Oracle
tenancy, NO third-party bucket, NO operator-held encryption. Anything that
would ship the vault (or its metadata columns) to an unmodelled party makes
THREAT_MODEL §2.6 and ADR-0021 false — that is a red line, not a tradeoff.

### 10.1 What is copied, and how

`sqlite3 ".backup"` — NEVER `cp` a live WAL database (a torn copy can look
fine and restore broken). Everything sensitive in the file is ciphertext
(ADR-0018/D-36/D-66), so a faithful byte copy needs no keys.

### 10.2 Layer 1 — on-host daily snapshots

The mechanism is VERSION-CONTROLLED, not hand-typed onto the box (a script
that exists only on one server is lost with it — the same lesson as every
hand-run Xcode step):

- `deploy/skipti-backup.sh` — the snapshot: `sqlite3 .backup` (NEVER `cp`
  on a live WAL database), then `integrity_check` + `user_version` on the
  COPY before it counts as a backup, atomic rename, sha256 manifest line,
  14-snapshot retention. Fails loudly on every path (`set -euo pipefail`,
  `.bail on`, explicit `die`).
- `deploy/skipti-backup.{service,timer}` — the units (04:17 UTC daily,
  `Persistent=true` so a down box catches up at boot).

```bash
# on the instance, from a repo checkout (scp deploy/ over if needed):
sudo install -m 700 deploy/skipti-backup.sh /usr/local/sbin/skipti-backup
sudo cp deploy/skipti-backup.service deploy/skipti-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now skipti-backup.timer
systemctl list-timers skipti-backup.timer   # must show a pending run
```

### 10.3 Layer 2 — off-host via the tenancy's own volume backups

In the Oracle console (or CLI): the instance's boot volume → **Backup
Policy** → daily incremental, 7-day retention. This is the layer that
survives the instance itself. It stays tenancy-internal and encrypted at
rest under tenancy keys — that is precisely why it is the chosen mechanism
(ADR-0030, option 4).

### 10.4 Restore verification — the drill (quarterly, and after any schema migration)

`deploy/skipti-restore-drill.sh` is the drill, runnable any time without
touching the live relay: integrity of the restored copy, row-count parity
for EVERY table (a table present in one and not the other is schema-drift
alarm, not a pass), byte-identity of each table's newest row, then a SECOND
relay booted against the restored file on loopback demanding `/` and
`/version`. Corrupt backups fail LOUDLY (proven: a byte-flipped snapshot is
refused at step 1 with the sqlite error carried through).

```bash
sudo deploy/skipti-restore-drill.sh              # newest snapshot, vs live vault
sudo deploy/skipti-restore-drill.sh /path/to/specific-snapshot.db
```

Record the drill's final `REPORT: BACKUP RESTORABLE` line: date, backup
filename, the `/version` answer of the restored instance. A backup whose
restore has never been rehearsed is a hypothesis (the §5.9 lesson, one
layer up). Proven here end to end against an authentic relay vault: 4/4
steps green and the restored instance answered with the production commit
hash; the first runs of these scripts caught and fixed three real defects —
a phantom retention prune, a relay port that ignored its environment, and
an orphaned cargo-wrapped server holding the caller's pipe open.
(Corrected D-170: "proven here" meant proven on macOS, whose `/bin/sh` is
bash. Both scripts began `#!/bin/sh` and use `set -o pipefail`; on the Ubuntu
host `/bin/sh` is dash, which refuses it, so the first production backup died
on line one. Both now run under `#!/bin/bash`.)

Use the installed relay binary on the host, so the drill does not rebuild
with cargo:

```bash
cd ~/skipti && sudo SKIPTI_SERVER_BIN=/usr/local/bin/skipti-server bash deploy/skipti-restore-drill.sh
```

**Drill log** (newest last):

| Date (UTC) | Snapshot | Restored `/version` | Result |
|---|---|---|---|
| 2026-09-25 23:49 | `relay-vault-20260925-234819.db` (106,496 B, sha256 `1ea8ab52…a5883`) | `4cf79fd` (schema v5, D-156) | 4/4, BACKUP RESTORABLE: the first production backup and drill (D-170) |

---

## 11. Retirement: the domain moves to Firebase Hosting, then Oracle shuts down

Written in D-199 (the Firebase plan's unit 9); run **with the 0.4.0 release** (unit 10), not before: until then
installed 0.3.x builds still sync through this relay. Firebase Hosting already serves the same `/join` page and
app-link files at `https://skipti-26.web.app` (D-199, checked there). Every step is the founder's. Do them in order:
the domain must answer from Firebase before the relay stops, or invite links break in between.

**1. Add the custom domain in Firebase** (console → Hosting → Add custom domain → `skipti.vardrlabs.com`). The
console shows the exact DNS records: a TXT record that proves ownership, then the A record(s) to point at. Nothing
here can be filled in ahead of time; copy them from that screen.

**2. Change DNS in Cloudflare** (vardrlabs.com → DNS): add the TXT record; **replace** the `skipti` A record (the
Oracle IP) with Firebase's, never add alongside it; keep the record **DNS only** (grey cloud), as today, or Firebase
cannot issue its certificate. If the zone has CAA records, they must allow `pki.goog` (Google's CA). The console says
when the domain is connected and the certificate issued (minutes to a few hours).

**3. Check the domain answers from Firebase** (on the Mac):

```bash
dig +short skipti.vardrlabs.com
curl -sI https://skipti.vardrlabs.com/join | grep -iE '^HTTP|content-security|referrer'
curl -s https://skipti.vardrlabs.com/.well-known/apple-app-site-association
curl -s https://skipti.vardrlabs.com/.well-known/assetlinks.json
curl -sS -G 'https://digitalassetlinks.googleapis.com/v1/statements:list' \
  --data-urlencode 'source.web.site=https://skipti.vardrlabs.com' \
  --data-urlencode 'relation=delegate_permission/common.handle_all_urls'
curl -s -o /dev/null -w '%{http_code}\n' https://skipti.vardrlabs.com/version
```

Expect: Firebase's addresses; `HTTP/2 200` with the `script-src 'sha256-EFFNKyFe…'` policy; the AASA with
`applinks` only; one assetlinks statement with both fingerprints; Google's validator listing `com.vardrlabs.skipti`;
and **404** for `/version` (the relay no longer answers on the domain). Apple's CDN refreshes the AASA within a day.

**4. A last backup, then stop the relay** (the instance's IP is in the founder's notes, not in this repo):

```bash
ssh-add ~/.ssh/skipti_oracle
ssh ubuntu@<instance IP>
sudo systemctl start skipti-backup.service
sudo ls -l /var/backups/skipti | tail -3
sudo systemctl stop skipti-relay caddy
sudo systemctl disable skipti-relay caddy skipti-backup.timer
exit
```

**5. Copy everything off the instance**, then prove the copy opens:

```bash
mkdir -p ~/SkiptiOracleFinal && chmod 700 ~/SkiptiOracleFinal
ssh ubuntu@<instance IP> 'sudo tar -C /var -czf - backups/skipti lib/skipti' > ~/SkiptiOracleFinal/oracle-final-$(date -u +%Y%m%d).tar.gz
tar -tzf ~/SkiptiOracleFinal/oracle-final-*.tar.gz | head
shasum -a 256 ~/SkiptiOracleFinal/oracle-final-*.tar.gz
```

The archive holds the relay's vault: testers' accounts and their sealed records. Keep it on the Mac's encrypted
disk only, for as long as the founder decides (record the date it is deleted in BUILD-LOG).

**6. Terminate the instance** (Oracle Cloud console → Compute → Instances → the SKIPTI instance → Terminate, with
"Permanently delete the attached boot volume" ticked), and delete its boot-volume backups (Block Storage → Boot
Volume Backups; ADR-0030's layer 2). Irreversible: only after step 5's archive opened.

Afterwards: the `deploy/` scripts and §1 to §10 of this file describe a relay that no longer runs; they stay as the
record of how it ran.

## What this does not cover

- **Monitoring and alerting** — none configured; a crashed relay is silent
  beyond systemd's restart.
- **The signed FX rate file** — §5.2's reference rates still need a real
  fetcher and a production operator key; the dev key is pinned and labelled.
- **`/version` is deployed but still reporting `"unknown"`** until the next
  deploy passes `SKIPTI_GIT_COMMIT` (step 7). The endpoint itself exists and
  is verified locally.
- **Rate limiting at the edge** — the relay's own OTP caps exist, but nothing
  bounds request volume in front of it.
