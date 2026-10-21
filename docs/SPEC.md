# SKIPTI

**Local-first, end-to-end encrypted personal finance and shared expense splitting.**
Web · iOS · Android · MIT · No subscriptions · No ads · No telemetry

Spec version 1.0 · design freeze candidate · not yet implemented

> **Superseded in part, 2026-10-01 ([ADR-0040](adr/0040-firebase-replaces-the-relay.md)).** SKIPTI's cloud is
> Google Firebase, not an end-to-end-encrypted relay. Where this document describes the relay, sealed sync,
> group-key wrapping, envelopes, the Recovery Kit, six-word safety phrases, passkeys, email codes or device
> approval (§2, §3.2–§3.5, §4, §6, and the related checklist and test-plan rows), ADR-0040 and
> `docs/superpowers/specs/2026-10-01-firebase-backend-design.md` decide instead. The local vault, money,
> splitting, categorisation, export, design and store sections stand.

---

## 0. Read this part first

### 0.1 The name

Old Norse **skipti**: a division, a share-out, an exchange. The verb *skipta*
means to divide, to split, and to exchange money. It is one word that already
contains both halves of the product: the ledger of transactions and the act of
splitting them. Same reason RÚNA worked, a word that means two things you need
at once.

Display: **SKIPTI**. ASCII for repo, CLI, package, subdomain: `skipti`.

Alternates if it fails a trademark check: `hlutr` (a share, a portion), `deila`
(to divide, though in modern Icelandic the noun also means a dispute, which is
funny but wrong), `skil` (settlement, rendering of accounts).

**Do the trademark search before the logo.** Fintech is a crowded class and you
learned that lesson on the last project. USPTO TESS, EUIPO, and a plain web
search, in classes 9, 36, and 42, before you commit to anything visual.

Subsystems, named consistently, and together they are the data flow:

| Module | Norse source | What it is |
|---|---|---|
| `lykill` | a key | Key derivation, device keys, recovery, unlock |
| `skrin` | a reliquary casket | The encrypted local store |
| `straumr` | a stream, a current | Sync transport and conflict resolution |
| `norn` | the fates who reckon what is owed | Ledger and debt graph |
| `hlutr` | a share, a portion | Split computation |
| `kvitt` | even, square, settled | Settle-up flows |
| `merki` | a mark, a sign | Categorisation and rules |
| `spá` | to foresee | Analytics, charts, forecasting |
| `bók` | a book | Export and reports |

### 0.2 Four hard things in your brief, answered up front

You asked for supreme security, cross-device sync, shared splitting, SMS
transaction scraping, three app stores, no subscriptions, and MIT. Some of those
pull against each other. Here is where, and what this spec does about it.

| # | The tension | Verdict | Resolution |
|---|---|---|---|
| 1 | **"Supreme security" vs "read my SMS inbox"** | These are opposites. Full SMS read is the single most invasive permission on Android: OTPs, balances, account fragments, and every personal message you ever received. | On-device parsing only, raw SMS never leaves the phone, never syncs, never touches a server, and is discarded after extraction. See §7. |
| 2 | **SMS scraping vs actually shipping on Play** | Google's policy is explicit and it has been enforced against exactly this use case for years. Budgeting apps have been refused. | Ship it as an Android-only sideload/F-Droid flavour, with a Play build that has the permission absent from the manifest entirely. See §7.2. This is not a workaround to sneak past review, it is two honestly different builds. |
| 3 | **SMS scraping on iOS** | Not possible. There is no API. There never has been. | iOS gets receipt OCR, share-sheet capture, CSV/OFX import, and Shortcuts automation instead. See §7.5. |
| 4 | **E2EE vs shared group ledgers** | Doable, but it costs server-side settlement, server-side search, content in push notifications, and painless password reset. | Per-group content keys, envelope-wrapped per member. All settlement computed on the client. Explicit recovery kit instead of a reset link. See §3. |
| 5 | **No subscriptions vs servers cost money** | Someone always pays. Pretending otherwise is how free apps quietly become ad apps. | Local-first by default, sync is optional, and the sync backend is pluggable: your own server, your own cloud drive, or a hosted instance funded by donation. See §6.2. |

### 0.3 What this is competing against, factually

Worth writing down because it defines the wedge.

<cite index="72-1">Splitwise's free tier in 2026 limits how many expenses you can log, shows ads between screens, and keeps receipt scanning and currency conversion behind Pro.</cite> <cite index="74-1">Reports put the free cap at roughly 3 to 5 expenses per day with a 10-second cooldown between entries.</cite> <cite index="76-1">Pro runs about $59.99 a year individually, with a Duo plan around $89.99, and pricing varies by region.</cite> <cite index="75-1">In India it lands near ₹2,499 a year.</cite> <cite index="70-1">The company was acquired in 2024 and the paywall tightening followed.</cite>

The complaint in every review is the same and it is not about price: **the daily
cap fires on the single most basic action the app performs, at the exact moment
a group is trying to use it.** A trip generates twenty expenses in a day. A cap
of three means the ledger is wrong for a week.

So the wedge is not "cheaper Splitwise." It is:

1. **No limits on the core loop, ever.** Logging an expense is never gated,
   metered, delayed, or interrupted. This is a written product guarantee in §1.3,
   not a launch promotion.
2. **The server cannot read your finances.** Nobody in this segment offers this.
   It is the one feature a competitor with a subscription business model
   structurally cannot copy, because their ads and their card-linking need
   plaintext.
3. **Your data leaves as easily as it arrives.** Full export, open format, no
   tier gate, and an importer that reads Splitwise and Tricount exports on day
   one. Make switching to you a ten-second operation.
4. **It works with no account and no network.** Account is opt-in, sync is
   opt-in, and the app is fully functional with neither.

### 0.4 How to read this

§1 to §3 are decisions you cannot reverse later without a rewrite. §4 to §10 are
implementation surface. §11 is design. §12 is the part that decides whether you
are allowed to ship at all, and it should be read before you build, not after.
§15 is the working checklist. §16 holds the agent prompts.

Where sections conflict, §2 (threat model) and §12 (store policy) win, in that
order. Security beats convenience, and policy beats both, because a feature that
gets the app removed ships to nobody.

---

## 1. Product definition

### 1.1 One sentence

A finance app that keeps your ledger on your own devices, encrypts it before it
ever reaches a server, splits shared costs without limits or ads, and hands your
data back in an open format whenever you ask.

### 1.2 The three jobs

1. **Personal ledger.** Where did my money go. Categories, charts, budgets,
   multi-currency, and a monthly picture that is actually legible.
2. **Shared splitting.** Who owes whom, across a trip, a flat, a couple, or a
   one-off dinner. Minimum-transfer settlement. Multi-currency groups.
3. **Getting data in without typing.** Receipt OCR, SMS parsing where the
   platform permits it, statement import, and share-sheet capture.

Most competitors do one or two. Splitwise does splitting and bolts on weak
personal tracking. Wallet does personal tracking and bolts on weak splitting.
Doing both properly, on one ledger, is the product.

### 1.3 Product guarantees

These are load-bearing promises. They go in the README, the store listing, and
`GUARANTEES.md`, and breaking one is a fork-worthy event.

- **No subscription. No ads. No telemetry. No analytics SDK. Ever.**
- **No cap, meter, cooldown, or interstitial on logging an expense.** Not now,
  not as a growth experiment later.
- **No feature is gated behind payment.** There is no paid tier to gate into.
- **Your data is exportable in full, in an open format, at any time, in one tap.**
- **The app is fully functional offline and with no account.**
- **No third-party network origin — except the sign-in provider you choose.**
  The app talks to your chosen sync backend and nothing else. No CDN, no fonts,
  no crash reporter, no attribution SDK. Tapping Sign in with Google or Apple
  lets that provider's system sign-in screen talk to that provider, and nothing
  more (amended 2026-09-14, ADR-0035).
- **We cannot read your data,** and §2 states precisely what that does and does
  not mean.

### 1.4 Non-goals

- **Not a bank aggregator.** No Plaid, no screen-scraping credentials, no
  account linking. That is a regulated business with a compliance surface you do
  not want and cannot afford. See §13.2.
- **Not a payments app.** SKIPTI records that a debt was settled. It never moves
  money. Settle-up hands off to UPI, Venmo, or a bank app via a deep link. This
  keeps you out of money transmitter licensing entirely, and that is not a
  detail, it is the difference between a weekend project and a regulated entity.
- **Not a lending, credit, or BNPL product.** Those categories carry the heaviest
  Play Store restrictions in existence.
- **Not an investment tracker** in v1. Scope discipline.
- **Not multi-user real-time collaborative editing.** Expenses are append-mostly
  records, not a shared document. Sync is eventual, not live.
- **Not an AI financial advisor.** Categorisation is a local classifier. It does
  not give advice, make predictions it cannot support, or send your ledger to a
  model provider.

### 1.5 Success criteria

- Log an expense from cold app launch in under 4 seconds, offline.
- 5,000 transactions, 12 groups: every screen renders in under 100 ms.
- A group of 6 across 3 currencies settles to the minimum number of transfers,
  and every member's device computes the same answer independently.
- A Splitwise export imports with balances matching to the minor unit.
- Network capture during a full session shows exactly one destination host and
  no readable financial content.
- Full export of 5,000 transactions to XLSX and PDF in under 10 seconds.
- Cold install to first logged expense with no account and no network: works.

---

## 2. Threat model

### 2.1 Assets, ranked

1. **Transaction ledger.** Amounts, merchants, dates, notes. This reveals
   medical conditions, relationships, addictions, politics, and location history.
   A spending log is more intimate than a chat log and is treated as such here.
2. **Content encryption keys.**
3. **Raw SMS content on Android.** Higher sensitivity than the ledger itself,
   because it contains OTPs and material from every other sender on the device.
4. **Group membership and social graph.** Who you split with.
5. **Identity bindings.** Email, phone, OAuth subject IDs.
6. **Metadata.** Transaction counts, sync timing, device count.

### 2.2 Adversaries

| ID | Adversary | Assumed capability | In scope |
|---|---|---|---|
| A1 | Passive network observer | Reads all traffic, cannot break TLS | Yes |
| A2 | Honest-but-curious sync operator | Full server disk, RAM, logs | Yes |
| A3 | Malicious sync operator | A2 plus can alter, drop, reorder, and serve modified web JS | Partially, see §2.4 |
| A4 | Hostile group member | Legitimately holds the group key | Partially, see §2.5 |
| A5 | Someone who picks up your unlocked phone | Physical access, device unlocked | Yes, see §3.7 |
| A6 | Someone who steals your locked phone | Physical access, device locked, no biometrics | Yes |
| A7 | Forensic extraction from a seized device | Chip-off, exploit chains, cloud backup subpoena | Best effort, honestly bounded |
| A8 | Malicious third-party SDK in your own app | Supply chain | Yes, and the answer is to ship no third-party SDKs |
| A9 | Compromised OS, root, or targeted malware | Full device control | **No.** Nothing at this layer helps. |
| A10 | Coercion of the user | Rubber hose | **No.** No deniability features, no fake vault. |

### 2.3 What SKIPTI actually defends

- **A1:** TLS plus an independent AES-256-GCM layer. A full TLS break yields
  ciphertext.
- **A2:** The sync server holds ciphertext blobs, opaque group IDs, and wrapped
  key envelopes. No plaintext amount, merchant, note, or category ever reaches it.
- **A5 and A6:** The local store is encrypted with a key held in the platform
  keystore, released only on biometric or device credential, with an app-level
  auto-lock. The screen blurs on backgrounding and screenshots are blocked on
  ledger screens.
- **A7:** Keys live in Secure Enclave and StrongBox where available. No cloud
  backup of the vault, on either platform, and this is enforced with backup
  exclusion flags rather than left to defaults.
- **A8:** There are no third-party SDKs. The dependency budget in §10.5 is a
  security control.

### 2.4 The honest limit: the web build is a trust relationship

Same unfixable problem RÚNA has and every browser-delivered E2EE product has. A
server that decides to serve one targeted user a modified bundle can exfiltrate
their key, and the encryption inside that bundle cannot prevent it.

Mitigations, all of which will be implemented: no third-party script origins,
strict CSP with SRI on every asset, reproducible builds with the release hash
published in the git tag and served at `/version`, `cosign`-signed artifacts,
and a self-host path that is one `docker compose up`.

**The native apps do not have this problem in the same shape,** because the
binary is signed, store-distributed, and does not re-fetch executable code at
runtime. That asymmetry gets stated plainly in the docs rather than glossed
over: if the operator is in your threat model, use the mobile app or self-host.

### 2.5 The honest limit: a group member sees the group

Anyone in a group can read every expense in it, screenshot it, and export it.
Removing them stops future access and nothing else. Group encryption protects
the group from the server, not members from each other.

Where this actually bites, and the mitigations:

- **Removal must rotate the key.** Otherwise a removed member with a copy of the
  sync blobs keeps reading new expenses. §3.6.
- **A hostile member can corrupt shared history.** Every mutation is signed by
  its author's device key, so corruption is attributable and reversible, not
  anonymous. §5.7.
- **Personal transactions are never in a group.** Two separate encryption
  domains, and no code path moves data from personal to group without an
  explicit user action. This is enforced by type, not by convention.

### 2.6 Metadata the sync server learns

Publish this table. Users of a finance app deserve it more than most.

| Leak | Why | Mitigation |
|---|---|---|
| Your IP | It is a TCP connection | No IP logging, Tor and VPN work fine |
| That an account exists | Routing | Unavoidable with an account |
| Number of devices | Envelope count | Unavoidable |
| Approximate transaction count and record sizes | Ciphertext volume | Padding to 256-byte buckets |
| Sync timing | Frame timing | Batching plus jitter, opt-in randomised delay |
| Group sizes and membership shape | Envelope fan-out | Unavoidable in a server-relayed design |
| Nothing about amounts, merchants, categories, or notes | Encrypted | Not applicable |

### 2.7 What is explicitly out of scope

- A compromised or rooted device (A9). Root detection will be implemented as a
  warning, and the docs will say plainly that it is a speed bump, not a control.
- Coercion (A10). No decoy vaults. A decoy vault is a feature that gets people
  hurt when it is discovered, and it invites a false sense of safety.
- Screenshots by a group member (A4).
- Shoulder surfing.
- Correlation of settle-up transfers by your bank or UPI provider. SKIPTI never
  sees them, but your bank does.

---
## 3. Security architecture

### 3.1 Primitives, fixed

| Purpose | Primitive | Parameters |
|---|---|---|
| Password KDF | Argon2id | m=64 MiB, t=3, p=1, 32-byte output, RFC 9106 |
| Key separation | HKDF-SHA-256 | Per-purpose `info` strings |
| Content AEAD | AES-256-GCM | 12-byte counter nonce, 16-byte tag |
| Key wrapping | AES-256-GCM (KW envelope) | One envelope per recipient device |
| Device identity | Ed25519 | Per-device, generated in secure hardware where possible |
| Group key agreement | X25519 ECDH | Per recipient device |
| Local DB encryption | SQLCipher (AES-256) | Key from platform keystore |
| Randomness | Platform CSPRNG | Never `Math.random`, never a userland PRNG |

Rejected: PBKDF2 as primary (behind guidance), any home-rolled construction, any
"encryption" that is actually just obfuscation, and storing a key alongside the
data it protects, which is the most common way finance apps fail this.

**As built (D-200, after ADR-0040):** core uses three of these and no more. SQLCipher with a 32-byte key from
the platform CSPRNG, held by the platform keystore; the platform CSPRNG for ids; and Ed25519 to VERIFY the signed
FX-rate and merchant tables (no device signs anything). SHA-256 makes stable category ids. Argon2id, HKDF,
AES-256-GCM envelopes, key wrapping and X25519 went with the relay, and their crates left core's dependencies
(D-200). Records are not signed: §5.7's per-record signature was never built.

### 3.2 The core problem: sharing and E2EE at the same time

Personal-only E2EE is easy. Shared-ledger E2EE is where products give up and
quietly decrypt server-side. The structure that works:

```
                       Master Key (MK)
                   32 bytes, never leaves device
                    never sent to any server
                              |
        +---------------------+---------------------+
        |                     |                     |
   Personal DEK          Group Wrapping         Device Auth
   HKDF info=            capability             HKDF info=
   "skipti/v1/personal"                         "skipti/v1/auth"
        |                     |                     |
   encrypts your        for each group G:      proves this device
   own ledger           GroupKey_G is          to the sync server
                        random 32B, wrapped
                        once per member device
                        via X25519 ECDH
```

**Group key distribution:**

```
1. Alice creates group G. Client generates GroupKey_G = random 32 bytes.
2. Alice invites Bob. Bob's device publishes an X25519 public key.
3. Alice's device: shared = X25519(alice_priv, bob_pub)
                   wrapKey = HKDF(shared, info="skipti/v1/groupwrap" || G || epoch)
                   envelope = AES-GCM(wrapKey, GroupKey_G)
4. Envelope uploaded. The server stores an opaque blob it cannot open.
5. Bob's device unwraps with its own private key. Only Bob can.
```

The server sees: a group ID, a member count, and N opaque envelopes. It can tell
you are in a group with three people. It cannot tell you spent ₹4,200 on a hotel.

**Verify your peers or the ECDH is theatre.** A malicious server can hand Alice
its own public key instead of Bob's, wrap the group key to itself, and read
everything. Standard MITM, standard fix, and skipping it is the most common way
"E2EE" apps are E2EE only against adversaries who are not the operator.

- Every device key gets a **6-word safety phrase** from a fixed 2048-word list.
- The group screen shows each member's phrase.
- Verification is a QR scan when together, or reading six words aloud.
- Unverified members render in the WATCH state colour with the label
  `UNVERIFIED`. Not hidden in a submenu. Visible on the group screen.
- If a member's key changes, every other member sees
  `Bob's device key changed. Verify again before adding expenses.` and new
  envelopes are held until acknowledged.

### 3.3 Where the Master Key comes from

Three unlock paths, and the user picks at setup. This is a real fork in the
product and the copy has to be honest about it.

| Mode | MK source | Recovery if device lost | Server can help? |
|---|---|---|---|
| **Device-only** | Random 32B in Secure Enclave / StrongBox | Recovery Kit only | No, and it never can |
| **Passphrase** | Argon2id over a user passphrase | Passphrase, or Recovery Kit | No |
| **Passphrase + escrow** | Argon2id, plus MK wrapped by a passphrase-derived KEK and stored server-side | Passphrase, remembered | No, the server holds only a wrapped blob it cannot open |

Default: **Passphrase + escrow.** People lose phones, and a finance app that
loses five years of records because someone dropped a phone in a lake is a bad
product no matter how good the crypto is. The escrow blob is useless without the
passphrase, so the server still cannot read anything, and the user still holds
the only thing that opens it.

`skipti/v1/*` info strings, all distinct:
`personal`, `groupwrap`, `auth`, `escrow`, `search`, `backup`, `fingerprint`.

### 3.4 Recovery Kit

Password reset is the feature that quietly destroys E2EE in most products,
because "email me a reset link" means the server can regenerate access, which
means the server has access. SKIPTI does not have password reset. It has a
Recovery Kit, generated at setup, before the user has data to lose.

```
        SKIPTI RECOVERY KIT
        Account   you@example.com
        Created   2026-08-23

        anchor  velvet  ember  thistle  quarry  lantern
        harbor  cobalt  meadow  ferrous  drift   nine

        This is the only way back in if you lose your devices
        and forget your passphrase. We cannot recover it for you.
        Nobody can. Print this. Put it where you keep your passport.
```

- 12 words from a 2048-word list, 132 bits. BIP-39 wordlist, since it is
  battle-tested, translated, and has a checksum, though this is not a wallet and
  the docs must not imply that it is.
- Generated **at setup**, and the flow requires confirming three random words
  back before proceeding. No skip button. A dismissable prompt gets dismissed.
- Downloadable as PDF and printable. Never emailed, never synced, never
  screenshotted into a photo library by the app itself.
- Re-viewable later behind a fresh biometric prompt.
- One in-app nudge at 7 days if never viewed again, then silence. Nagging trains
  people to ignore the app.

**As built (D-175, the founder, 2026-09-26):** the kit is generated at
**sign-in**, not at first launch. A new person opens SKIPTI and uses it; after
Apple or Google confirms a sign-in, the twelve words are shown and three are
confirmed (no skip) before the sign-in counts as done, and a launch that finds
a session without a kit finishes this first. The reason: the founder found the
first-launch ceremony a wall before anyone understood the app, and without an
account the words restore nothing (the ledger's key is a random key in the
phone's keychain). **The restore path from the words is not built yet**
(nothing derives a key from them; a new phone gets its data only by approval
from an existing device, §3.5): it is scheduled before any public release, and
until then no copy calls the kit "the only way back in".

### 3.5 Devices

- Every device generates its own Ed25519 signing key plus X25519 agreement key,
  in Secure Enclave (iOS `kSecAttrTokenIDSecureEnclave`) or Android Keystore
  with StrongBox where available, falling back to TEE, with the actual backing
  reported to the user rather than assumed.
- Adding a device is an explicit approval on an existing device showing the new
  device's safety phrase, model, and location hint. Never a silent join.
  **As amended (ADR-0039, the founder, 2026-09-29):** this is the **Maximum
  security** behaviour. By default a new phone on the account gets the key
  automatically from a phone that holds it, which then shows a notice with the
  new phone's six words (so a join is never silent); Maximum security (Settings
  → Privacy & security) restores the approval, with "They match, share" and
  "Not me". Model and location hint are not shown (not built).
- **Device list is a first-class screen**, not a settings sub-page: every device,
  its last sync, its platform, its safety phrase, and a Revoke button.
- Revoking a device triggers rotation of every group key that device held, and
  re-wrapping of the personal DEK.
- Web sessions are always listed as devices, and expire in 30 days by default.

### 3.6 Key rotation

| Trigger | Rotates | Rationale |
|---|---|---|
| Member removed from group | That group's key, new epoch | They keep old blobs, they get nothing new |
| Device revoked | Personal DEK plus every group key it held | Same |
| Passphrase change | Escrow KEK only, not the MK | Cheap, no re-encryption of history |
| User requests it | Everything, with a progress screen | A finance app should let you burn it all down |
| Suspected compromise | Everything, plus force re-verification of all peers | |

Epoch number ships in every record header from v1, even though rotation lands in
v1.1. Adding a header field before launch is a line of code. After launch it is
a migration.

### 3.7 Local device security, concretely

| Control | iOS | Android | Web |
|---|---|---|---|
| Encrypted store | SQLCipher, key in Keychain, `WhenUnlockedThisDeviceOnly` | SQLCipher, key in Keystore, `setUserAuthenticationRequired` | IndexedDB, records encrypted individually, key in memory only |
| Biometric gate | Face ID / Touch ID via LocalAuthentication | BiometricPrompt, `BIOMETRIC_STRONG` | WebAuthn where available |
| Auto-lock | Default 2 min background, configurable, never "off" without a warning | Same | Tab close or 15 min idle |
| Screenshot block | `isSecureTextEntry` overlay on ledger screens | `FLAG_SECURE` | Not possible, and say so |
| Task switcher blur | Blur overlay on `willResignActive` | `FLAG_SECURE` covers it | Not applicable |
| Cloud backup | `NSURLIsExcludedFromBackupKey`, Keychain not synced to iCloud | `allowBackup=false`, `dataExtractionRules` excludes the vault | Not applicable |
| Clipboard | Clear amounts from clipboard after 30 s; `UIPasteboard` items marked local-only | `ClipData` flagged sensitive on API 33+ | Clear on blur |
| Root / jailbreak | Detect and warn, never block | Detect and warn, never block | Not applicable |

**As built (D-163, D-164; the founder, 2026-09-25):** screenshot blocking is a
setting, **off by default**, so a balance can still be screenshotted and sent
to the group, and a tester can capture a bug. On, Android sets `FLAG_SECURE` on
the whole window. iOS offers no API to block screenshots, so there the setting
hides the ledger while the screen is recorded, mirrored or shared, and says
so; the `isSecureTextEntry` trick was not used, being unsupported and fragile
across iOS versions. The app switcher is covered regardless (iOS: a plain
cover when not active; Android 13+: no Recents screenshot while the app lock is
on). The auto-lock is built as the row says. The clipboard holds only the
invite link: on iOS it is local-only and expires in 2 minutes (a link needs
long enough to paste into a message), and on Android it is flagged sensitive.

Two positions worth defending. **Never block on root detection:** it is trivially
bypassed by anyone who matters and it punishes power users who are often your
best contributors. Warn, log nothing, move on. **Never make auto-lock silently
"off":** allow it, but make the user pass a confirmation that says what they are
giving up.

### 3.8 What is deliberately not built

- **Cloud key escrow that the operator can open.** The whole point.
- **Server-side search.** Impossible by construction. Search is local, over the
  decrypted store, and is fast because the dataset is small.
- **Content in push notifications.** A notification saying "Bob added ₹4,200
  Hotel" routes plaintext through APNs and FCM. Notifications say
  `New activity in Goa Trip` and nothing more. Content is fetched and decrypted
  after the app opens.
  **Amended 2026-10-19 (D-235, the founder's decision 120):** as built since D-196,
  a group push carries the change's own line, the author, the note and the
  amount as core formats it ("Al added Dinner · $40.00"), titled with the group's
  name; whether the lock screen shows it is the phone's own preview setting
  (CLOUD-SECURITY G-19). The text passes through APNs and FCM in the clear, as
  the cloud is not end-to-end encrypted (ADR-0040). The sentence above no longer
  describes the app.
- **Deniable or decoy vaults.** §2.7.
- **Biometric-only accounts with no passphrase or kit.** A stolen face is not a
  credential you can rotate.

---

## 4. Identity and authentication

### 4.1 Account is optional, and that is the point

The app must be fully usable with no account at all. Local ledger, local groups
via QR-code invite over a local network, full export. An account exists only to
enable sync across devices and remote group members. Making the account optional
is both a privacy stance and a conversion strategy: the user gets full value
before being asked for anything.

### 4.2 Sign-in methods

| Method | Purpose | Notes |
|---|---|---|
| **Passkey / WebAuthn** | Primary, recommended | Phishing-resistant, no shared secret on the server, works across all three platforms in 2026 |
| **Sign in with Apple** | Required if any other third-party sign-in is offered, see §12.2 | Use the private relay email, never ask for the real one |
| **Google Sign-In** | Convenience | Scope `openid` only. System sign-in surface only (no embedded SDK on iOS); Android `play` flavour only; relay-issued nonce; the relay consumes `sub` alone (ADR-0035) |
| **Email OTP** | No password to leak, no password to reuse | 6 digits, 10 min TTL, single use, 5 attempts then lock |
| **Phone OTP** | India-relevant, high UPI overlap | Expensive and SIM-swappable, see below |

**Deliberately absent: passwords.** No password field, no password database, no
password reset, no credential stuffing surface, no bcrypt-versus-argon debate.
Passkeys plus OTP cover every case and remove an entire category of breach. If
this proves too aggressive for adoption, the fallback is email OTP, not passwords.

**Two things about the identity layer that matter more than they look:**

1. **Authentication identity is completely separate from encryption identity.**
   Signing in with Google gives you access to *ciphertext*. It does not and
   cannot give you the Master Key. If someone compromises your Google account,
   they get encrypted blobs and a passphrase prompt. Say this in the onboarding
   copy, because users assume the opposite.
2. **SIM swap is a real attack on phone OTP,** and it is common in India. Phone
   OTP therefore authenticates a *session*, never authorises a device addition,
   a key rotation, or an escrow unwrap. Those always require an existing device
   or the Recovery Kit.

**As built (D-173, the founder, 2026-09-26):** the relay sends no email, so an
email code could never arrive; both apps hide the email code, and with it the
passkey, which is only ever added to a session the code proved (D-102). Sign-in
is Apple (iPhone) and Google (iPhone and the Android `play` flavour) until an
email sender exists. The Android `direct` flavour, which carries nothing from
Google, then has no way to sign in and says so.

### 4.3 Session and token handling

- Access tokens: 15 minutes, in memory only, never persisted.
- Refresh tokens: 30 days, rotating on every use, stored in Keychain or Keystore,
  never in `localStorage` on web (use a `Secure`, `HttpOnly`, `SameSite=Strict`
  cookie plus a CSRF token).
- Refresh token reuse detection: if an old token is presented, revoke the whole
  family and force re-authentication. This is what turns a stolen token into one
  window instead of permanent access.
- **As built on iOS and Android (D-162, 2026-09-25; the founder asked for the
  choice best for speed and security):** both tokens live inside the SQLCipher
  vault, whose key is held by the Keychain (`WhenUnlockedThisDeviceOnly`) or the
  Keystore and never leaves the device, and the vault is excluded from every
  backup (§3.7). A separate Keychain item would have the same protection class,
  so it adds nothing. An access token held only in memory would force a refresh
  on every launch, and because a replayed refresh token revokes the whole family
  (above), two refreshes racing at launch would sign the person out. The web
  build keeps the cookie rule above.
- Every session appears in the device list with revocation.
- Rate limits: 5 OTP requests per identifier per hour, 10 verification attempts,
  then a 15-minute lock with exponential backoff. Constant-time comparison and a
  250 ms floor on the auth path so timing does not become an account-existence
  oracle.
- Enumeration: sign-in responses for "no such account" and "wrong code" are
  identical in body and in timing.
- *One account per phone (the founder, 2026-09-25; as built D-168):* a phone's
  ledger belongs to the first account that signs in on it, and signing out keeps
  it that account's. A sign-in by a different account is refused before its
  session is stored or anything syncs, the session it created is ended on the
  relay, and the person is told why and offered Export, then a confirmed
  "Erase this phone's ledger" (the vault and its key; setup starts again). The
  same erase is in Settings → Your data, for handing on a phone. Nothing is
  ever erased automatically.

### 4.4 Account deletion

Apple requires in-app account deletion, and it is the right behaviour anyway.

- One screen, reachable from Settings in two taps, no email required, no
  retention dark pattern, no "are you sure" repeated four times.
- Deletes: account record, all ciphertext blobs, all envelopes, all device
  records, all group memberships. Not a soft-delete flag.
- 7-day grace with a cancel link, clearly stated, then irreversible.
- Offers a full export **before** deletion, in the same flow.
- Groups where the user is the last member are destroyed. Groups with others
  survive with the member removed and keys rotated.
- Deletion is verified by a test that queries the store afterwards, not by
  trusting the ORM.
- **As built (D-198, ADR-0040's Firebase cloud):** Settings → Delete account → one
  screen (what goes, what stays, "Export your data first") → one confirmation. Every
  phone is signed out at once and every session revoked; the account is deleted 7
  days later by a daily job unless the person signs in and taps "Keep my account"
  (the cancel is in the app: SKIPTI holds no email address to send a link to).
  Deleted: the profile, username, phones, personal records, invitations, counters,
  unused invite links and the sign-in account; groups with others keep what this
  person added (there are no group keys to rotate since ADR-0040). The ledger on the
  phone stays. firebase/tests/account.test.mjs queries the store afterwards.

---
## 5. Money, ledger, and splitting

### 5.1 The rule that prevents the most bugs

**Money is an integer of minor units plus an ISO 4217 currency code. Always.**

```ts
type Money = {
  amount: bigint;       // minor units: 12345n = ₹123.45 = $123.45
  currency: string;     // "INR" | "USD" | "JPY" | ...
};
```

- **Never a float.** `0.1 + 0.2 !== 0.3` is not a curiosity, it is a bug report
  from a user whose group balance is off by two paise and who now does not trust
  the app. A finance app that uses floating point anywhere in the money path is
  broken, and no amount of rounding at the display layer fixes it.
- **Minor unit exponent varies.** USD and INR are 2. JPY and KRW are 0. BHD,
  KWD, and OMR are 3. Hardcoding 100 breaks in exactly the countries you will
  ship to.
- **Never sum across currencies without an explicit rate and timestamp.**
- Lint rule: any arithmetic on a value typed `Money` outside the `norn` module is
  a build failure.

### 5.2 Currencies and FX

- Full ISO 4217, with INR, USD, EUR, GBP, JPY, AED, SGD, AUD, CAD promoted in
  the picker.
  *As built (D-165, 2026-09-25):* the picker shows the nine, then the
  person's default currency and the current choice, then "All currencies", a
  searchable list of every code core accepts (`ffi_currency_codes`; core's
  table is close to, not exactly, ISO 4217: BUILD-LOG §4, D-165 (a)). The
  default currency is a per-phone preference in Settings, never synced;
  unset, it is the phone region's currency when core knows it, else USD. It
  pre-selects a new group's settlement currency and a fresh expense; a
  group's own currency still wins on Add.
  **As changed (D-244, ADR-0043, the founder, 2026-10-20):** with an account
  the default currency the person chose follows the account to its other
  phones (a personal `setting` record, the latest write wins); without an
  account it stays on the phone, and an unset default is never synced.
- **Every transaction stores three things: the original amount and currency, the
  FX rate used, and the rate's timestamp.** Rates move. A trip settled in
  February must not silently re-price in March because the rate changed. The
  rate is part of the record, not a lookup at render time.
- Rate source: a daily reference set, fetched as a signed static file, cached
  for 24 hours, and working offline against the last known set with a visible
  staleness indicator. No per-transaction API call, which would leak your
  spending pattern to an FX provider.
- Group settlement currency is fixed at group creation and displayed everywhere.
  Members can enter in any currency; conversion happens at entry with the rate
  frozen into the record.
- **Indian numbering.** `₹12,34,567.89` in the lakh/crore grouping when locale is
  `en-IN`, not `₹1,234,567.89`. This is the single most common giveaway that an
  app was not built for the market, and getting it right buys more goodwill than
  a feature would.
- Manual rate override per transaction, because the rate your card actually gave
  you is not the reference rate, and people know it.

### 5.3 Core entities

```
Account       a real-world source of money: cash, a bank account, a card,
              a wallet. Personal only. Never shared.
Transaction   one movement of money. Belongs to exactly one Account.
              Optionally linked to a Group via a Split.
Category      hierarchical, two levels. See §8.1.
Merchant      normalised counterparty, learned from imports.
Group         a shared ledger with members and one settlement currency.
Split         how one Transaction divides among Group members.
Settlement    a record that a debt was paid. NEVER moves money. See §5.8.
Budget        a per-category cap for a period.
Rule          a user-defined mapping from a pattern to a category. §8.3.
Attachment    a receipt image or PDF, encrypted, chunked, stored separately.
```

Two boundaries that must be enforced in the type system, not by convention:

1. **An Account never belongs to a Group.** Your bank balance is not group data.
2. **A Transaction in a Group is encrypted with the group key; a personal one
   with the personal DEK.** Moving between them is an explicit re-encryption
   operation with a confirmation, never an implicit field change.

**As built (D-249, 2026-10-21): "Just me".** A personal transaction is the Expense record with no group (the personal
scope): payer you, one share, yours. The app calls this ledger "Just me": the first choice in Add expense's Ledger row
(it hides the split), its own page ("Your spending", with "All my spending" adding your share of every group expense),
and a Home section below the groups. It never enters a balance, Settle up or People, syncs only through the account's
own log, and is in every export. Moving a transaction between Just me and a group is not offered yet (the confirmed
operation above is still owed).

### 5.4 Split types

Match every competitor and then add the two they charge for.

| Type | Description |
|---|---|
| Equal | Divide evenly, with remainder handling per §5.5 |
| Exact | Explicit amount per person, must sum to the total |
| Percentage | Must sum to 100% |
| Shares | Weights, e.g. 2 adults + 1 child as 2:2:1 |
| Adjustment | Equal split plus or minus a per-person delta |
| Itemised | Line items assigned to people, plus tax and tip apportioned |
| Payer-only | One person paid, nobody owes: a personal expense logged in a group |

**Free in SKIPTI, paid elsewhere:** itemised splitting, saved group default
splits, and currency conversion. <cite index="69-1">Splitwise makes you re-assign shares by hand on every single expense, because saving a group default split is a Pro-only feature.</cite> That is a
five-line feature held hostage, and shipping it free is worth more than a
paragraph of marketing.

**Saved splits** are a first-class object: name a split ("Rent 60/40", "Family
shares"), attach it to a group as the default, and override per expense.

### 5.5 Remainders, which is where correctness is actually tested

₹100 split three ways is 33.33, 33.33, 33.34. That last paisa has to go
somewhere, deterministically, or two devices compute different balances and the
group stops trusting the app.

```
Algorithm (largest remainder, deterministic):
  1. base = floor(total / n) for each participant
  2. remainder = total - (base * n)          // in minor units, always < n
  3. Sort participants by a STABLE key: participant UUID ascending.
  4. Distribute one minor unit to the first `remainder` participants.
  5. Assert: sum(shares) == total. This assertion runs in production.
```

Sorting by UUID rather than by name or join order means every device computes an
identical result with no coordination, which is what makes offline-first
splitting possible at all. The UI notes who absorbed the extra unit, and a
group setting can rotate the burden across expenses so the same person is not
always paying the extra paisa.

Property test: for all totals 1 to 1,000,000 and all n from 2 to 50, shares sum
exactly to the total and no two shares differ by more than one minor unit.

### 5.6 Debt simplification

Reducing "everyone owes everyone" to the fewest transfers is a min-cost-flow
problem. The practical approach:

```
1. net[p] = sum(paid by p) - sum(owed by p)     per currency
2. Split into creditors (net > 0) and debtors (net < 0).
3. Greedy: repeatedly match the largest debtor to the largest creditor,
   transfer min(|debt|, credit), remove whoever hits zero.
4. Yields at most n-1 transfers. Not provably minimal in every case, but
   optimal in practice and O(n log n).
5. Never net across currencies. Simplify per currency, then present
   per-currency settlements.
```

**Make simplification opt-in per group, defaulting off.** This is a UX judgement
with real weight: simplification produces "you owe Carol ₹500" when you never
transacted with Carol, and people find that confusing and occasionally
suspicious. Show the raw who-owes-whom by default, offer Simplify as a visible
toggle with a one-line explanation, and always keep the underlying pairwise
ledger inspectable.

**The pairwise view cancels loops (D-218).** A debt that only goes round in a
circle (A owes B, B owes C, C owes A, as after a Settle all that skips a middle
person) is nobody's debt, so the pairwise view (the balance line's entries and
the People cards built from them) cancels every directed cycle by its smallest
edge, taking cycles in a fixed order by ascending participant id, the same for
every viewer, and after any removed-person collapse (D-210). Every net, the
Settle all plan and the totals are unchanged, and cancelling never adds a pair
the matrix did not already have (only D-210's collapse of a removed person can
route a debt into a new pair), so this is not the simplification above and
needs no toggle. The founder, 2026-10-14 and 2026-10-16.

### 5.7 Records are signed and append-mostly

- Every mutation carries `authorDeviceId` and an Ed25519 signature over its
  canonical CBOR encoding.
- Edits create a new version; the prior version is retained until compaction.
  A group ledger where someone can silently change a number is not a ledger.
- Deletes are tombstones, never hard removals, until a compaction pass that
  every member's device performs independently.
- Every expense shows `added by` and `last edited by` with a timestamp. This is
  a social feature as much as a security one: it ends arguments.

### 5.8 Settle-up moves no money, ever

This is the most important architectural boundary in the product and it is a
legal boundary, not a technical preference. See §13.2.

```
User taps Settle Up
  -> SKIPTI computes the amount
  -> SKIPTI records a Settlement (a ledger entry, signed, encrypted)
  -> SKIPTI hands off:
       India:  UPI deep link  upi://pay?pa=...&am=...&cu=INR&tn=...
       US:     Venmo / Cash App / PayPal.me deep link
       EU:     SEPA payment request QR (EPC069-12)
       Else:   copy bank details, or just mark as paid
  -> The user completes payment in their own bank or payments app
  -> SKIPTI never sees an account number, never touches funds
```

The instant funds flow through your system you are a money transmitter in the US,
a Payment Aggregator under RBI rules in India, and a PSD2-regulated entity in the
EU. That is not a compliance checklist, it is a licensed business. Deep links
give the user the same three taps and keep SKIPTI a notebook.

UPI links get first-class treatment given the INR requirement: pre-filled payee,
amount, and a transaction note carrying the group name. Support `upi://` plus
the app-specific intents so the picker is not empty.

---

## 6. Sync

### 6.1 Local-first, sync as an accessory

The database of record is on the device. Sync propagates changes. The app is
fully correct with sync switched off forever.

**CRDT rather than last-write-wins.** Two people editing the same expense on
different phones with no network must merge deterministically. Practically:

- The ledger is a set of signed, immutable operations. Sets merge trivially.
- Field-level edits use a last-writer-wins register per field with a hybrid
  logical clock, so a category change on one device and a note change on another
  both survive.
- Amount conflicts are the one case surfaced to the user rather than
  auto-resolved: `Two devices set different amounts. Keep ₹1,200 or ₹1,250?`
  Silently picking one is how a ledger loses money.
- Library: Automerge or Loro. Yjs is optimised for text and is the wrong shape
  here. Decision in §17.1.

### 6.2 Pluggable backends, which is how "no subscription" survives contact with reality

Someone pays for every byte. Making the backend the user's choice is the only
honest resolution.

| Backend | Who pays | Best for |
|---|---|---|
| **None** | Nobody | One device, maximum privacy |
| **Own server** | The user, one `docker compose up` | Self-hosters, the default in the README |
| **Own cloud drive** | Nobody extra, uses existing iCloud / Google Drive / Dropbox quota | Most individuals. Encrypted blobs in their own storage. |
| **Hosted instance** | You, funded by donations, with published costs | People who want it to just work |
| **LAN / QR** | Nobody | Group sync in the same room, no internet at all |

The cloud-drive backend is the underrated one. The app writes encrypted blobs to
the user's own Drive or iCloud folder using the platform's document APIs. Zero
server cost, zero operator trust, and the sync story is "your data is in your
Drive." That is a better privacy pitch than anything a competitor can print, and
it costs you nothing per user, forever.

### 6.3 Sync protocol shape

```
Record = { header (plaintext), ciphertext }

header: version | recordType | groupId or personal | epoch | hlc timestamp |
        authorDeviceId | nonce_sess | flags
ciphertext: AES-256-GCM over canonical CBOR, AAD binds every header field
```

- Sync by log index, not by state vector. State vectors leak per-device operation
  counts; an index leaks strictly less.
- Batch and coalesce on a 5-second window when foregrounded, immediately on
  background, always on manual pull-to-refresh.
- Pad to 256-byte buckets.
- Optional randomised sync delay of 0 to 120 seconds for users who care about
  timing correlation, off by default, in Settings under Privacy.
- Attachments are chunked at 256 KiB, encrypted per chunk with a per-attachment
  key wrapped by the record key, uploaded lazily and fetched on demand rather
  than eagerly, because receipt images are the bulk of the bytes.

### 6.4 Server responsibilities, deliberately minimal

The sync server stores blobs and relays them. It does not compute balances,
resolve conflicts, count anything about your finances, or understand a single
field. Same discipline as the RÚNA relay: **if you find yourself writing a
server function that inspects record contents, the design has already failed.**

Rust, Axum, Postgres for blob metadata and envelopes, object storage for
attachment chunks. Stateless, horizontally scalable, and small enough to run on
a 1 GB VM for a few hundred users.

---
## 7. Getting transactions in without typing

This section contains the biggest correction to your brief, so it is the longest.

### 7.1 SMS parsing is a real feature and a real problem

The feature is genuinely valuable in India, where nearly every debit generates a
bank SMS and the alternative is manual entry. It is also the highest-risk thing
in the entire product, on two independent axes.

**Axis 1: privacy.** `READ_SMS` grants the entire inbox. Not bank messages: all
of them. OTPs, medical results, messages from a partner, everything anyone has
ever texted the user. An app that encrypts a ₹200 coffee expense to a
military standard and then reads the whole SMS inbox has an incoherent threat
model, and a reviewer will say so publicly.

**Axis 2: it will probably get rejected from Google Play.** This is not
speculative and not a matter of writing a better justification.

<cite index="52-1">Google's policy states that apps lacking default SMS, Phone, or Assistant handler capability may not declare use of SMS permissions in the manifest, including placeholder text, and usage is limited to documented core functionality that is absolutely essential to the app's primary purpose.</cite> <cite index="51-1">Bluecoins, an established budgeting app, applied for an exception for exactly this SMS banking-transaction feature and was denied, on the grounds that SMS is highly sensitive, the app is not a default SMS handler, and the purpose was deemed not core functionality.</cite> <cite index="60-1">A developer in 2026 reports the same outcome: SMS auto-import rejected for a finance app, with Google responding that manual input is an available alternative, and the app shipping with the feature disabled.</cite> <cite index="48-1">The spyware policy adds that budgeting apps may not exfiltrate or share non-financial or personal SMS history.</cite>

So plan for refusal as the base case, not the exception.

**One clarification that matters, because it is the first objection anyone
raises:** on-device processing is a genuine and sufficient answer to Axis 1. It
is not an answer to Axis 2, because Google's criterion is not "what do you do
with the data." It is "what kind of app are you." Bluecoins processed on device
and was refused. The 2026 rejection above came from an app whose own description
says all data stays on device and it was refused with the same reasoning. Doing
the right thing with the permission does not make you eligible to hold it. See
§7.2b for the one path that does.

### 7.2 The two-build resolution

Not a trick to get past review. Two honestly different products, clearly labelled.

| Build | Distribution | SMS | Everything else |
|---|---|---|---|
| **SKIPTI (Play)** | Google Play | **Permission absent from the manifest entirely.** Not requested, not declared, not present as placeholder text. | Full app |
| **SKIPTI Direct** | F-Droid, GitHub Releases, signed APK | `READ_SMS` + `RECEIVE_SMS`, opt-in, on-device only | Identical |

Implementation: an Android product flavour where the SMS module is excluded at
compile time from the Play variant, verified by a CI check that greps the built
Play AAB's merged manifest for SMS permissions and fails the build if found.
Placeholder strings in the manifest are themselves a violation, so this has to be
a build-time exclusion, not a runtime flag.

In-app, the Play build shows the feature with an honest explanation and a link:
`Automatic SMS import is not available in the Play Store version. Google's
policy restricts SMS access to default messaging apps. The direct-download
version has it.` No dark patterns, no "coming soon" for something that is not
coming.

**Also note:** <cite index="50-1">Google requires developers offering financial products and services to register as an Organization on Play.</cite> Budget the time for that, and note that
it means a personal developer account will not be sufficient.

### 7.2b The default-handler path, or "how does Truecaller do it"

Worth answering directly, because it is the obvious counterexample and the answer
is instructive rather than discouraging.

**Truecaller qualifies because it is a messaging app.** <cite index="82-1">It takes over the default Phone and SMS apps and sets itself as the default handler for both.</cite> <cite index="84-1">Once set, it lists your messages in its own client with Inbox, Unread, Transactions, OTP, and Spam categories.</cite> It sends SMS, schedules SMS, translates SMS, and handles MMS. It also occupies two other
enumerated exceptions at once: caller ID, and spam detection and blocking.

So Truecaller is not a finance app that won an argument. It is a full SMS and
dialler replacement that also parses transactions. The policy is being applied to
it exactly as written.

**That path is open to SKIPTI in principle.** It is also the wrong trade, for
four reasons, and they compound:

1. **You would have to build a real SMS client.** Send, receive, threads, MMS,
   RCS, delivery reports, dual SIM, scheduled send, search, blocking, backup. Not
   a stub. Google requires that an app registered as a default handler actually
   perform that function, and a token messaging tab is exactly what review looks
   for.
2. **The user has to make your expense tracker their messaging app.** That is the
   real cost, and it is not a permission prompt, it is a lifestyle change. Ask
   yourself whether you would replace your messaging app with a budgeting tool.
   Truecaller earned that position over fifteen years by being useful for calls
   first.
3. **It inverts the security posture.** SKIPTI's entire claim is that it holds as
   little as possible and encrypts what it holds. A default SMS handler *owns
   every message the user ever receives, permanently, as its core function.* The
   threat model would go from "we hold your encrypted ledger" to "we hold your
   ledger and your entire message history," which is a worse product wearing a
   better feature.
4. **It doubles the attack surface and the maintenance burden** for a
   single-developer MIT project, and every carrier quirk in every market becomes
   your bug report.

Note also that <cite index="82-1">Truecaller as default SMS app is widely flagged as a privacy concern in its own right,</cite> including by people who happily use its caller ID.
That reputational cost is one Truecaller can absorb. A security-first finance app
cannot.

**Verdict: the door exists, and walking through it turns SKIPTI into a different
product.** Recorded as ADR 0014 so the decision is deliberate and a future
contributor does not reopen it as a good idea.

### 7.2c The notification listener, which is a real middle path

`BIND_NOTIFICATION_LISTENER_SERVICE` reads notifications posted by other apps,
including bank apps and UPI apps, without touching SMS at all. Several Indian
finance apps use it for exactly this.

Honest assessment:

- **Better standing than `READ_SMS`.** Different permission, different policy
  surface, and no enumerated default-handler requirement.
- **Still restricted, still declared, and still reviewable.** Google treats
  notification access as sensitive and it has been tightened repeatedly. Do not
  assume approval; build it so its removal does not break the app.
- **Better privacy shape by accident.** A bank notification is already a
  summarised transaction. There are no OTPs from your doctor in it, and there is
  no inbox history to backfill.
- **Worse coverage.** It only works if the user has the bank app installed with
  notifications enabled, which in India is often but not always.

Same rules as §7.3 apply in full: on-device only, source allowlist, derived
fields only, no backfill, complete revocation. **Recommendation: build the
notification listener before the SMS parser.** It gets most of the coverage at a
materially lower policy and privacy cost, and it can ship in the Play build with
a declaration rather than requiring a separate distribution channel.

### 7.2d Correction: the sideload escape hatch is mostly closed

This invalidates part of §7.2 and it is important enough to state loudly rather
than quietly patch.

<cite index="103-1">Google Play Protect automatically blocks installation of apps from internet-sideloading sources (browsers, messaging apps, file managers) when those apps declare RECEIVE_SMS, READ_SMS, NOTIFICATION_LISTENER, or ACCESSIBILITY, because these permissions are frequently abused for financial fraud.</cite> On top of that, <cite index="110-1">Android 13's Restricted Settings blocks sideloaded apps from being granted Notification Listener or Accessibility access at all, though the user can override it from the App Info page, and apps installed through an app store's session-based package installer are unaffected.</cite>

So "just ship a Direct APK with the sensitive permission" is not the clean
fallback §7.2 assumed. A finance app carrying `READ_SMS`, distributed by
download, is precisely the pattern Play Protect exists to stop, and it will be
stopped for most users regardless of intent. Whether F-Droid's installer path
avoids this depends on which installer the client uses and should be tested
empirically rather than assumed.

**Consequence, and it changes the recommendation:** the only distribution
channel where automatic capture works reliably is the Play build itself.
Notification listener is therefore not a nice-to-have alternative to SMS. It is
the only version of this feature that can actually reach users, because it is
the only one that can plausibly live in a Play-distributed app.

### 7.2e Implementation plan for notification capture

#### Manifest and service

```xml
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
                 tools:ignore="ProtectedPermissions" />

<service
    android:name=".capture.SkiptiNotificationListener"
    android:label="@string/capture_service_name"
    android:exported="false"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
  <intent-filter>
    <action android:name="android.service.notification.NotificationListenerService" />
  </intent-filter>
  <!-- Narrow what the OS delivers at all. Bank and UPI notifications are
       alerting; conversations are not wanted and are excluded here rather
       than filtered later. -->
  <meta-data android:name="android.service.notification.default_filter_types"
             android:value="alerting" />
  <meta-data android:name="android.service.notification.disabled_filter_types"
             android:value="ongoing|silent|conversations" />
</service>
```

The `default_filter_types` meta-data is doing real work: it narrows delivery at
the OS boundary instead of receiving everything and discarding. That is both a
better privacy story and a better answer during review, because the reduction is
verifiable from the manifest rather than being a claim about your code.

#### Grant flow

Notification access is not a runtime permission. It is granted on a system
settings screen, which means no `requestPermissions()` and no standard dialog.

- Android 11+: deep-link straight to the app's own entry with
  `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` plus the component name.
  Falling back to `ACTION_NOTIFICATION_LISTENER_SETTINGS` drops the user into a
  long list where most of them give up.
- Show a **prominent in-app disclosure before** sending them there, per Play's
  requirement: what is read, what is kept, what never leaves the device, and how
  to turn it off. This screen is also the artefact you point at in review.
- Poll `NotificationManagerCompat.getEnabledListenerPackages()` on resume to
  detect the grant, since there is no result callback.

#### Staying alive, which is the real engineering problem in India

The service gets unbound by the system, by OEM battery managers, and after
updates. Users experience this as "it just stopped working."

- Wait for `onListenerConnected()` before doing anything.
- Call `requestRebind()` on disconnect, and re-check binding on app foreground.
- **OEM battery killers are the dominant failure mode in this market.** MIUI,
  ColorOS, Funtouch, One UI, and others kill background services aggressively.
  Detect the manufacturer and show a targeted guide to whitelist the app,
  with a deep link to the relevant settings screen where one exists. Reference
  the community-maintained per-OEM instructions rather than writing your own.
- A visible health indicator in Settings: `Capture active · last event 12 min
  ago`. Silent failure in a capture feature is worse than no capture, because
  the user stops entering things manually and only notices the gap at month end.

#### Source discovery, user-driven

Do not hardcode a list of bank package names. It goes stale, it is wrong outside
your own bank set, and it is unmaintainable across markets.

```
Settings > Automatic capture > Choose sources

  These apps posted transaction-shaped notifications in the last 7 days:

  [ ] HDFC Bank        12 matches
  [ ] Google Pay       31 matches
  [ ] PhonePe           8 matches
  [ ] Swiggy            4 matches   (looks like an order, not a payment)

  SKIPTI only reads notifications from sources you tick.
```

The discovery pass records **package name and match count only**, never
notification content, and runs only while the user is on this screen. The
allowlist check is the first line of `onNotificationPosted`, before any call to
`getNotification().extras`. Everything else is dropped without being read.

#### Parsing

On-device, and the same rules as §7.3 apply in full.

- Extract: amount, currency, direction (debit or credit), counterparty or VPA,
  masked last four, reference number, timestamp.
- Handle Indian formats: `Rs.`, `INR`, `₹`, lakh grouping, `debited`/`credited`/
  `spent`/`paid`/`received`, and the common bank phrasing patterns.
- **OTP and balance-enquiry patterns are dropped by a pre-filter that runs
  first**, before amount extraction, so a code never enters the parse path.
- Everything is a **proposal**, never an auto-committed transaction. It lands in
  a review inbox with a badge. One tap accepts, one tap categorises, one swipe
  discards. A finance app that silently invents entries is worse than one that
  misses them.

#### Deduplication, which everyone gets wrong

One UPI payment can generate three notifications: the bank app, the UPI app, and
a bank SMS. Naive capture triples every expense and the user quits.

```
Dedup key: (amount, currency, ±90 s window, last4 or reference number)
Precedence: reference number > bank app > UPI app > SMS
Collapse into ONE proposal, showing all sources that contributed.
Never dedup across different amounts, even if close.
```

#### Ship order, and this part is strategic

**Do not put a sensitive permission in your first submission.** A rejection on a
first submission can put a new developer account under broader review. A
rejection on an update is an inconvenience.

```
v1.0   Ship with manual entry, share-sheet capture, Shortcuts, receipt OCR,
       and statement import. No notification listener. Get approved and live.
v1.1   Add notification capture as an update, with the disclosure screen, the
       Data Safety update, and the policy justification prepared in advance.
v1.2   Reassess SMS only if 1.1 was approved and users still ask for it.
```

Build the capture pipeline behind a feature flag during v1 so 1.1 is a flag
flip plus a manifest entry rather than new architecture under review pressure.
And design the whole feature so that **losing it breaks nothing**: capture is a
proposal source feeding a review inbox that also accepts manual entry, share
sheet, and OCR. If Google removes the capability tomorrow, the inbox still works
and the app is not diminished.

### 7.3 If SMS is implemented, these rules are absolute

1. **Parsing is 100% on-device.** No SMS body, fragment, or hash ever leaves the
   phone. Not for model improvement, not for debugging, not in a crash report.
2. **Only bank-shaped senders are read.** Match against a curated sender-ID list
   (`VK-HDFCBK`, `AD-ICICIB`, `JM-SBIINB`, and similar) plus a strict
   transaction-shaped regex. A message from a person is never parsed, never
   stored, never logged, and never enters memory beyond the sender check.
3. **Only the derived fields persist:** amount, currency, direction, merchant
   string, last four digits, timestamp. The raw SMS is discarded within the same
   function call, before any write.
4. **No inbox history sweep on install.** Forward-only from the moment permission
   is granted. Reading five years of backlog is where "expense tracker" turns
   into "surveillance."
5. **OTP messages are dropped before parsing,** by pattern, as the first check.
6. **A visible ledger of what was parsed:** every auto-created transaction shows
   `from SMS · HDFC Bank · 14:22` and is deletable, with a one-tap
   `stop parsing this sender`.
7. **Revocation is complete:** turning the feature off deletes every stored
   sender rule and parse artefact, not just the permission.

### 7.4 What to build instead, and first

Ranked by value per unit of risk. Every one of these works on all three
platforms, and together they cover most of what SMS parsing was for.

| Method | Platforms | Notes |
|---|---|---|
| **Statement import** | All | CSV, OFX, QIF, and MT940. A column-mapping UI that remembers per-bank layouts. Unglamorous and the single highest-value importer. |
| **Receipt OCR** | All | On-device: Vision on iOS, ML Kit on Android, Tesseract WASM on web. No image leaves the device. This is a paid feature in Splitwise. |
| **Share-sheet capture** | iOS, Android | Share a payment confirmation from any app into SKIPTI and it parses the text. The user initiates it, so no permission is needed at all. Covers UPI app confirmations neatly. |
| **Shortcuts / Tasker** | iOS, Android | Publish an intent and a Shortcuts action. Power users automate "when I close GPay, log an expense." Zero permission surface, and users have already built this pattern by hand. |
| **Notification listener** | Android | Reads bank app notifications rather than SMS. No default-handler requirement, no inbox history, and the only automatic-capture path that can realistically live in a Play-distributed build. Build this first. See §7.2c and §7.2e. |
| **Email forwarding** | All | The user forwards receipts to a per-account address. Parsing happens client-side after fetch. No Gmail scope, no CASA assessment. |
| **Widget and quick-add** | All | Home screen widget, lock screen, Apple Watch complication. Reducing manual entry to two taps competes surprisingly well with automation. |
| **Account Aggregator** | India, future | RBI's sanctioned rail for financial data. The correct long-term answer for INR, but it requires being a registered Financial Information User, which an MIT project is not. Note it and move on. |

**Recommendation: ship §7.4 in v1 and treat SMS as a v1.1 experiment on the
Direct build only.** The share-sheet plus Shortcuts combination gets most of the
convenience with none of the policy risk, and it works on iOS, which SMS never
will.

### 7.5 iOS: there is no SMS API, and there never has been

Say this plainly in the docs so nobody files it as a bug. iOS provides no
mechanism for a third-party app to read the message inbox. `ILMessageFilterExtension`
exists for filtering unknown senders, runs in a sandbox with no network access by
default, and cannot hand message content back to the containing app. SMS autofill
supplies a one-time code to a text field and nothing else. There is no
entitlement, no private path worth the rejection, and no roadmap item that will
change this. iOS parity comes from §7.4.

---

## 8. Categorisation and analytics

### 8.1 Category tree

Two levels. Deeper trees test well in a spec and fail in a hurry at a
restaurant table.

```
Food & Drink        Groceries · Restaurants · Cafés · Bars · Delivery · Snacks
Travel              Flights · Trains · Buses · Taxis · Fuel · Tolls · Parking · Rentals
Stay                Hotels · Rentals · Hostels
Shopping            Clothing · Electronics · Home · Beauty · Gifts
Bills & Utilities   Rent · Electricity · Water · Gas · Internet · Mobile · Subscriptions
Health              Doctor · Pharmacy · Insurance · Fitness
Entertainment       Streaming · Events · Games · Books · Hobbies
Transport           Public transit · Vehicle maintenance · Insurance
Education           Tuition · Courses · Supplies
Personal            Grooming · Laundry · Services
Financial           Fees · Interest · Taxes · Charity
Income              Salary · Refunds · Transfers in
Other               Uncategorised · Cash withdrawal
```

- Fully user-editable: rename, re-icon, re-colour, add, archive. Never delete a
  category with history; archive it and keep the records intact.
- Multi-currency safe: a category total is per-currency, or converted with the
  frozen per-transaction rate, never with a live rate at render time.
- Every category carries an icon and a colour that persist across all charts, so
  "the orange wedge" means the same thing everywhere.

### 8.2 Automatic categorisation, on device

1. **Merchant lookup.** A bundled table of roughly 5,000 normalised merchant
   patterns mapped to categories, shipped with the app, updated via a signed
   static file. `SWIGGY` becomes Food & Drink · Delivery.
2. **User rules.** Explicit and always highest priority. §8.3.
3. **Local classifier.** A small on-device model over the merchant string,
   amount band, and time of day. TFLite or Core ML, under 2 MB.
4. **Learning.** Correcting a category offers `always categorise SWIGGY as
   Delivery`, which creates a rule. The model retrains locally, nightly, on
   charge. Nothing is ever uploaded.

**No cloud inference. No LLM API call with a user's merchant history.** Sending
a spending log to a model provider would undo the entire security architecture
for a convenience that a 2 MB local model already delivers.

### 8.3 Rules engine

```
IF   merchant matches "UBER*"        THEN category = Travel · Taxis
IF   amount > 50000 AND account = HDFC   THEN flag for review
IF   merchant = "LANDLORD" AND day <= 5  THEN category = Rent, recurring
```

Conditions on merchant, amount range, account, currency, day of month, direction,
and source. Actions: set category, add tag, rename merchant, split by a saved
rule, flag, or ignore. Rules are ordered, testable against history with a preview
before saving, and exportable as JSON.

### 8.4 Charts

Every chart is a real answer to a real question, not decoration.

| Chart | Question it answers |
|---|---|
| Category donut, period-scoped | Where did it go |
| Stacked bar by month | Is this normal for me |
| Sankey: income to categories | Where does my salary actually end up |
| Calendar heatmap | Which days are expensive |
| Line with 3-month rolling mean | Trend, without the noise of one big month |
| Budget bullet bars | Am I over, and by how much |
| Group balance graph | Who owes whom, as a graph, before simplification |
| Merchant top 10 | The thing people are always surprised by |
| Cash-flow forecast | Given recurring items, what does next month look like |

Rules: all charts render offline from local data. Every chart has a table view,
because charts are not accessible on their own. Tap any segment to see the
transactions behind it, with no dead ends. Currency is always labelled, and
mixed-currency views state which rate and date were used.

### 8.5 Budgets and recurrence

- Per-category budgets, per period (weekly, monthly, custom), with rollover
  optional.
- Recurring transaction detection: same merchant, similar amount, regular
  interval. Detected, then **proposed**, never auto-inserted. A ledger that
  invents transactions is worse than one that misses them.
- Subscription audit screen: everything recurring, annualised, sorted by cost.
  Given the product's positioning against subscription apps, this is a
  particularly enjoyable feature to include.

---

## 9. Export and reports

### 9.1 Non-negotiable stance

Export is not a premium feature, has no row cap, no watermark, no delay, and no
"we'll email it to you." It is one tap and it produces a complete file. This is
a differentiator precisely because competitors treat data portability as
leverage.

### 9.2 Formats

| Format | Contents | Use |
|---|---|---|
| **XLSX** | Multi-sheet: Transactions, Groups, Settlements, Categories, Accounts, Budgets, plus a Summary sheet with live formulas | The one most people want |
| **CSV** | One flat file, RFC 4180, UTF-8 with BOM for Excel | Universal, scriptable |
| **PDF** | Formatted statement with charts | Sharing, filing, and taxes |
| **JSON** | Complete, lossless, schema-versioned | Full backup and migration. This is the format that makes the guarantee real. |
| **OFX / QIF** | Standard ledger interchange | Import into desktop finance software |

XLSX generation: `rust_xlsxwriter` on native, SheetJS on web. Number cells are
real numbers with a currency format, never strings, and never floats derived
from the minor-unit integers by division at write time. Dates are real dates.

### 9.3 PDF reports

Vector text, selectable, never a rasterised screenshot of a chart.

- Cover page: period, currencies, totals, the account or group name.
- Summary: category breakdown table plus a donut.
- Trend: month-over-month bars.
- Transactions: paginated table, grouped by month, with repeating headers.
- Group settlement: who owes whom, before and after simplification.
- Footer: page numbers, generation timestamp, and a note that amounts are as
  recorded.
- Charts render as SVG embedded into the PDF, not as PNG.
- **Strip metadata.** No author, no producer string with a device name. Default
  filename `skipti-export-2026-08.pdf`, never derived from a group name, because
  filenames leak into cloud backups and screen shares.

### 9.4 Warn before export

Same discipline as RÚNA's PDF path: export writes plaintext finances to disk or
to a share sheet, where the app's encryption no longer applies.

`This file is not encrypted. Anything you share it with can read it.`

Offer an optional passphrase-protected ZIP (AES-256, not the legacy ZipCrypto,
which is trivially broken) for anyone sending an export by email.

### 9.5 Import

Importers are how you win users from a competitor, so build them first, not last.

- **Splitwise export** (CSV), balances reconciled and verified.
- **Tricount, Settle Up, Splid** exports.
- **Wallet by BudgetBakers** CSV.
- **Generic bank CSV** with a column-mapping UI that remembers the layout per
  bank, handles Indian bank date formats and the `Dr/Cr` column convention, and
  previews before committing.
- Every import is a dry run first: show what will be created, flag duplicates by
  amount plus date plus merchant, and allow rollback of the entire import as one
  unit.

*As built (D-166, 2026-09-25):* Splitwise group exports and Wallet by
BudgetBakers exports can be imported from Balance (when there is no group
yet) and from Settings → Your data. An import always makes a NEW group: its
name comes from the file name, its settlement currency is the one most rows
use (changeable), and for Splitwise the person picks which column is them;
the other columns become the group's people. Nothing is saved until the
review's Import: the group is built but not stored, the plan is checked
against the ledger plus that group, and only after core accepts the merge
are the group and the chosen expenses written, as sync records, in one
vault session. Splitwise's member cells follow Splitwise's own convention:
positive for the person who paid, negative for those who borrowed (the D-69
reading was reversed; corrected in D-166). Not built yet: Tricount, Settle
Up, Splid, the generic bank CSV, importing into an existing group, and
undoing an import as one unit after it is applied (BUILD-LOG §4, D-166).

---
## 10. Client architecture

### 10.1 Platform strategy

One shared core, three native shells. The core is the part where a bug costs
money, so it is written once, in Rust, and compiled everywhere.

```
                   +-------------------------------+
                   |   skipti-core  (Rust)         |
                   |   lykill  keys, KDF, unlock   |
                   |   skrin   encrypted store     |
                   |   norn    ledger, balances    |
                   |   hlutr   split algorithms    |
                   |   merki   rules, categorise   |
                   |   straumr sync, CRDT merge    |
                   +---------------+---------------+
                                   |
              UniFFI                |               wasm-bindgen
        +----------+----------+     |     +---------+
        |                     |     |     |
   +----v-----+         +-----v-----v+   ++--------+
   |  iOS     |         |  Android    |   |  Web    |
   |  Swift   |         |  Kotlin     |   | TS +    |
   |  SwiftUI |         |  Compose    |   | React   |
   +----------+         +-------------+   +---------+
```

**Why not one cross-platform UI framework.** Flutter or React Native would ship
faster and produce an app that feels like neither platform. A finance app lives
or dies on trust, and trust is partly texture: the wrong scroll physics, a
non-native date picker, or a share sheet that is subtly off all read as
"unfinished." Native shells cost more and are the right call here.

**Why Rust for the core.** Money arithmetic, split remainders, CRDT merge, and
crypto must produce byte-identical results on every platform. Three
implementations means three subtly different rounding behaviours and a group
whose balances disagree by device. One core, compiled three ways, makes that
class of bug impossible.

### 10.2 Per-platform notes

**iOS:** SwiftUI, minimum iOS 16. Keychain with `WhenUnlockedThisDeviceOnly`,
Secure Enclave for device keys, LocalAuthentication for the biometric gate,
App Intents and a Shortcuts action, a Lock Screen widget for quick-add,
`ILMessageFilter` never (see §7.5), and StoreKit never (no purchases at all).

**Android:** Kotlin, Jetpack Compose, minSdk 26, targetSdk current. Keystore with
StrongBox where available, BiometricPrompt with `BIOMETRIC_STRONG`, `FLAG_SECURE`
on ledger screens, `allowBackup=false`, a Quick Settings tile, and the product
flavour split from §7.2.

**Web:** React plus TypeScript, Vite. IndexedDB with per-record encryption,
WebAuthn for unlock, service worker for offline, and installable as a PWA. Keys
in memory only, never persisted, so a reload requires re-unlock. Strict CSP with
Trusted Types.

### 10.3 Offline is the default state, not a failure state

Every action works with no network. The sync indicator has three honest states,
and none of them is a spinner that lies:

```
  SYNCED     everything is on your other devices        (temper SECURE)
  PENDING    3 changes waiting                          (temper WATCH)
  OFFLINE    working locally, will sync when connected   (temper COLD)
```

No blocking modal, ever, for a network condition. No "connect to continue." If
the app cannot reach the server, it says so in the status line and carries on.

### 10.4 Performance budgets

| Metric | Budget |
|---|---|
| Cold start to interactive | < 1.2 s on a mid-range Android from 2022 |
| Add expense, tap to saved | < 200 ms, fully local |
| Ledger scroll, 5,000 rows | 60 fps, virtualised |
| Balance recompute, 12 groups | < 50 ms |
| Chart render | < 100 ms |
| Full XLSX export, 5,000 rows | < 10 s |
| App size | iOS < 30 MB, Android < 25 MB per ABI |

### 10.5 Dependency budget as a security control

Hard cap: **30 direct production dependencies per platform.** Every addition
after freeze requires a PR justification stating what it does, who maintains it,
its release cadence, and what it replaces.

Banned outright, enforced in CI by a manifest scan:

- Every analytics SDK: Firebase Analytics, Amplitude, Mixpanel, Segment.
- Every crash reporter that transmits off-device by default. Crash reports are
  opt-in, scrubbed, and user-visible before sending, or absent.
- Every ad SDK and attribution SDK: AppsFlyer, Adjust, Branch.
- Every session-replay tool. Recording a finance app's screens is grotesque.
- Any dependency that requires a privacy manifest declaring tracking.

This list is a feature. It goes in the README.

---

## 11. Design system

### 11.1 Direction, and where it comes from

**As changed (D-232, ADR-0042, the founder, 2026-10-18):** a new install opens in Aurora dark: with no mode
stored, the app reads Dark. Light and System stay as choices, a stored choice always wins, and an install that ran
before D-232 keeps following the phone (a one-time launch migration stores System for it). "Light first" below,
and in ADR-0011, ADR-0023 and ADR-0038, no longer names the default mode. What it was for stands: light mode is a
requirement, designed and measured in every set that has one (Aurora light, Vardr Labs light), every pair measured
by the AppearanceTests on both shells.

**As built (D-174, ADR-0038, the founder, 2026-09-26):** after using the
internal builds on his own phones, the founder found this direction read as
engineering software to non-technical people and chose a friendlier one:
the same hues made vivid (cobalt primary, teal-green for money coming to you,
coral for money you owe), one typeface (Space Grotesk, tabular figures for
amounts; IBM Plex Mono only for the Recovery Kit's words and six-word
phrases), sentence-case labels instead of uppercase micro-labels, cards and
rounder, taller buttons (D-176 then moved the palette to "Aurora", a
violet-indigo primary over a soft northern-lights glow, with frosted glass
cards). ADR-0038 has the values, the measured contrast and
the colour-vision check; it supersedes §11.1's technical register, §11.2's
values, §11.3's monospace and micro-label rules, and turns §11.4's single
balance line into per-group and per-person breakdowns. Light first, Night,
§11.5–§11.7 and the security honesty of §11.6 stand. The text below is the
original direction, kept as the record.

Derived from the visible language of vardrlabs.com: dark technical surface,
hairline rules as the primary structural device, uppercase letterspaced
micro-labels with middot separators, monospace with tabular figures for every
number, and terse declarative copy with no marketing register.

**Hex values below are proposals. I did not scrape your site CSS.** Substitute
the real tokens before building. *(2026-08-24: done — real Vardr Labs tokens
adopted, verified, and extended in ADR-0023.)*

**One genuine adaptation is required.** SKIPTI needs a light mode and a bright,
readable one, which vardrlabs.com does not have to solve. A finance app gets
opened outdoors, at a restaurant table, one-handed, in a hurry. Dark-only is a
posture; light mode is a requirement. Build light first and derive dark from it,
because doing it the other way produces a light mode that looks like a dark mode
with the lights turned on.

### 11.2 Tokens

| Token | Light | Dark | Use |
|---|---|---|---|
| `--bg` | `#FAFAF9` | `#0A0B0C` | Page |
| `--surface` | `#FFFFFF` | `#121417` | Cards, sheets |
| `--surface-raised` | `#F4F4F2` | `#1A1D21` | Toolbars, input rows |
| `--hairline` | `#E4E4E1` | `#22262B` | 1px rules, the main structural device |
| `--text` | `#16181A` | `#E6E8EA` | Body |
| `--text-dim` | `#5A6169` | `#8A939B` | Labels, metadata |
| `--text-faint` | `#8A939B` | `#5A6169` | Timestamps, minor counts |

**Money colours, and only these two carry semantic weight:**

| Token | Light | Dark | Meaning |
|---|---|---|---|
| `--owed` | `#B4442A` | `#E8734F` | Money leaving you, or you owe |
| `--owing` | `#2E6B52` | `#5FB88C` | Money coming to you, or you are owed |
| `--neutral` | `#5A6169` | `#8A939B` | Settled, zero |

Reusing the temper-colour idea from RÚNA for **state**, not for money:

| State | Light | Dark | Meaning |
|---|---|---|---|
| `COLD` | `#8A939B` | `#8A939B` | Offline, local only |
| `SECURE` | `#4A7E9B` | `#7FA8C9` | Synced, all peers verified |
| `WATCH` | `#B08428` | `#E8C170` | Pending, unverified member, stale FX rate |
| `ALERT` | `#B4442A` | `#E8734F` | Key changed, sync failed, budget exceeded |

Red and green are the wrong pair for money because roughly 1 in 12 men cannot
distinguish them. The chosen tokens are a warm rust and a cool teal-green,
distinguishable under deuteranopia and protanopia, and **the sign is always
printed** (`-₹1,240` and `+₹340`) so colour is never the only carrier.

### 11.3 Type

| Role | Face | Rules |
|---|---|---|
| Display | Your site's display face | Screen titles, balances, the number on the settle-up screen |
| Body | A readable text face, 16px/1.5 | Descriptions, notes, help |
| **Numeric** | Monospace, tabular figures | **Every amount, count, date, percentage, and currency code.** No exceptions. This is your existing house rule and it is exactly right for a ledger, where columns of numbers must align. |
| Micro-label | Uppercase, 11px, `letter-spacing: 0.14em` | `GOA TRIP · 6 MEMBERS · SETTLED IN INR` |

Self-host every font. No Google Fonts. A font request tells a third party that a
user opened their finance app.

### 11.4 The signature: the balance line

Every finance app has a balance number. Most render it as a big coloured figure
and stop. Make the number carry its own explanation, in one monospace line that
appears identically on the home screen, the group screen, and the widget:

```
   ₹ 4,280.00                  you are owed
   ────────────────────────────────────────
   RAHUL   +2,100   PRIYA  +1,480   AMIT  +700
```

Rather than a donut nobody reads, the balance decomposes inline into who and how
much, in tabular figures, sorted by magnitude. It answers "how much" and "from
whom" in the same glance, which is the only question anyone opens a splitting
app to ask.

The same treatment for a category: the amount, then the top three merchants
inline beneath it. One pattern, reused everywhere, is what makes an interface
feel designed rather than assembled.

### 11.5 Motion

Fluid means responsive, not animated. The budget:

- Shared-element transition from list row to detail, 220 ms, spring.
- Number transitions roll digit by digit when a balance changes, 300 ms. This is
  the one indulgence, and it earns its place because it draws the eye to a
  changed number.
- Sheet presentation follows platform default. Do not re-implement it.
- Everything else: 120 ms opacity or transform.
- `prefers-reduced-motion` removes all of it, including the digit roll.
- **No skeleton shimmer.** The data is local. If a screen needs a loading state,
  something is architecturally wrong.

### 11.6 Copy

Terse, declarative, no marketing register, and never overstating the security.

| Situation | Write this | Not this |
|---|---|---|
| Encryption explainer | `Encrypted on your device. The server stores ciphertext it cannot read.` | `Bank-grade security` |
| Recovery kit | `This is the only way back in. We cannot recover it for you. Nobody can.` | `Keep your recovery code safe!` |
| Unverified member | `Priya's device is not verified. Compare her six words before adding expenses.` | `Unverified user` |
| Key changed | `Amit's device key changed. This is normal after a reinstall, but verify before continuing.` | `Security warning` |
| Offline | `Working locally. 3 changes will sync when you reconnect.` | `No internet connection` |
| Settle up | `Marks this as paid here. It does not move money.` | `Pay now` |
| Export | `This file is not encrypted. Anything you share it with can read it.` | (silence) |
| SMS, Play build | `Not available in the Play Store version. Google restricts SMS access to default messaging apps. The direct download has it.` | `Coming soon` |
| Stale FX | `Rate from 3 days ago. Converted amounts may be off.` | (silence) |
| Empty state | `Nothing here yet. Add your first expense, or import from another app.` | `No data available` |

Buttons keep their verb: `Settle` produces `Settling` produces `Settled`. Never
`Submit`, never `OK`.

### 11.7 Accessibility floor

- Contrast 4.5:1 body, 3:1 large text and UI boundaries, verified per token pair
  actually used, in both themes.
- Every chart has a table equivalent reachable in one tap.
- Amounts are announced correctly by screen readers: `minus one thousand two
  hundred and forty rupees`, not `dash one two four zero`. This needs explicit
  accessibility labels on every money view, because the default reading of a
  formatted currency string is wrong on both platforms.
- Dynamic Type and Android font scaling to 200% without clipping. Test the
  balance screen at 200%; it is where layouts break.
- Touch targets 44pt iOS, 48dp Android.
- Full keyboard navigation on web, visible focus rings, no `outline: none`.
- Colour never the sole carrier: signs are printed, states are labelled.
- Test with VoiceOver, TalkBack, and one keyboard-only pass.

---

## 12. Store compliance

Read this before building, not after. A feature that gets the app rejected ships
to nobody, and rework at review time is the most expensive rework there is.

### 12.1 Google Play

| Requirement | Action |
|---|---|
| **SMS permissions** | Play build: absent from the manifest, enforced by a CI check on the merged manifest. §7.2. |
| **Organisation registration** | <cite index="50-1">Required for developers offering financial products and services.</cite> Start this early; it involves document verification. |
| **Data safety form** | Declare honestly: data encrypted in transit and at rest, no data shared with third parties, deletion available in app. Every claim must match the code, and Google does check. |
| **Financial features declaration** | Complete it. SKIPTI is not a lending, payments, or investment app, and declaring that clearly is what keeps it out of the heaviest restriction tiers. |
| **Account deletion** | In-app plus a web URL. §4.4. |
| **Target API level** | Current requirement at submission time, and this moves annually. |
| **Permissions** | Camera (receipt OCR, optional), notifications (optional), biometric, contacts (optional: "Find friends in contacts", asked only on that tap; emails become SHA-256 fingerprints on the phone and the cloud keeps none; the founder, 2026-10-02, D-197). Nothing else. No location, no storage beyond scoped. |
| **Prominent disclosure** | Any sensitive permission needs an in-app disclosure before the system prompt, not just a privacy policy line. |

### 12.2 Apple App Store

| Requirement | Action |
|---|---|
| **Sign in with Apple** | Required if Google Sign-In is offered. Implement it properly, support the private relay address, and never require the real email. |
| **Account deletion** | In-app, discoverable, no support-ticket detour. §4.4. |
| **Privacy nutrition labels** | Declare accurately. With no analytics and no third-party SDKs, this is a short and unusually clean label, which is itself a marketing asset. |
| **Privacy manifest** | `PrivacyInfo.xcprivacy` declaring required-reason API usage. Third-party SDKs need theirs too, which is another reason to have none. |
| **Encryption export compliance** | Set `ITSAppUsesNonExemptEncryption`. Standard cryptography for authentication and data protection generally qualifies for exemption, but the declaration still has to be filed correctly. Confirm with counsel. §13.4. |
| **No purchases** | With no IAP and no external purchase links, the whole 3.1 payments section is inapplicable. Keep it that way. |
| **Donations** | If donation links appear at all, keep them out of the app binary. Put them on the website. This avoids an entire category of review argument. |
| **Guideline 4.2, minimum functionality** | Not a risk here, but the App Store listing must show the app doing real work, not just a promise. |

### 12.3 Both stores

- Privacy policy: hosted, plain language, versioned, honest, and no longer than
  a page. It should be readable as a document, not survivable as one.
- Age rating: 4+ / Everyone. No user-generated public content, no gambling.
- Screenshots must show real UI with realistic data, never mockups with invented
  numbers.
- No mention of competitors by name in store listings. Both stores dislike it and
  it reads as defensive.
- Do not use the word "bank" in the app name, subtitle, or icon. It invites the
  financial-services review path you have carefully avoided.

---

## 13. Legal, licensing, and regulatory

### 13.1 Licence

> **2026-08-24: Superseded by ADR-0020.** The project relicensed from MIT to
> Apache-2.0 (express patent grant and retaliation, trademark non-grant,
> contribution terms). Reasoning below preserved as written; it compared MIT
> only against AGPL.

MIT, as you specified, plus a `NOTICE` file. MIT permits anyone to fork,
rebrand, and monetise, and that is a deliberate choice worth understanding
rather than sleepwalking into. If it ever matters that a fork cannot go
closed-source, AGPL is the alternative, but it complicates App Store
distribution because of a long-standing tension between GPL-family terms and the
App Store's distribution terms. **MIT is the right call for a store-distributed
app.** Note the reasoning in an ADR so it is a decision rather than a default.

### 13.2 The regulatory line, and why §5.8 exists

SKIPTI records debts. It never moves money. That single boundary is what keeps
this a notebook instead of a regulated financial institution.

| If SKIPTI did this | It would become | Consequence |
|---|---|---|
| Hold or transfer user funds | A money transmitter (US), a Payment Aggregator (RBI), a PSD2 entity (EU) | State-by-state MTL, RBI authorisation, capital requirements |
| Link bank accounts by credentials | A data aggregator | Plaid-tier compliance, and in India, the Account Aggregator framework requires FIU registration |
| Offer credit, loans, or BNPL | A lender or a facilitator | The heaviest Play restrictions in existence, plus licensing |
| Give personalised investment advice | An investment adviser | SEC or SEBI registration |

Staying on the safe side of all four is a design constraint, not a limitation.
Write it into `CONTRIBUTING.md` so a well-meaning contributor does not add a
"just hold the balance for a second" feature that changes the company's legal
character.

### 13.3 Privacy law

- **DPDP Act (India):** consent, purpose limitation, and a mechanism for
  correction and erasure. §4.4 covers erasure. The consent flows must be
  granular, not one blanket accept.
- **GDPR (EU):** lawful basis, data minimisation, portability, and erasure.
  The architecture makes most of this easy, since the operator holds ciphertext
  and export is native.
- **CCPA/CPRA (California):** no sale of data, and say so explicitly.
- Do not claim "GDPR compliant" or "DPDP compliant" as a badge. State what the
  app does and let the reader conclude. §14.6.

### 13.4 Crypto export

Publishing encryption software from the US generally involves a one-time
notification to BIS and the NSA under EAR 740.13(e), and the App Store requires
an encryption declaration. Both are cheap to do correctly and annoying to do
late. **This is a pointer, not legal advice.** Ask an attorney, particularly
since a US-registered entity is involved.

### 13.5 Liability

Ship an honest disclaimer. This is not a bank, not an accountant, not tax advice,
and provided as-is. Publish the threat model and let people make an informed
choice. Do not carry a security claim in marketing that §2 does not support.

---
## 14. Test plan

### 14.1 Known-answer tests, non-negotiable

Never trust your own crypto wiring. Prove it against published vectors.

- **Argon2id:** RFC 9106 vectors.
- **HKDF-SHA-256:** RFC 5869 A.1 to A.3.
- **AES-256-GCM:** NIST CAVP vectors, **including one that must fail
  authentication**, so tag verification is proven wired rather than assumed.
- **Ed25519:** RFC 8032.
- **X25519:** RFC 7748.

### 14.2 Money correctness, which is where this app actually lives

| Target | Property |
|---|---|
| Split remainders | For all totals 1 to 10^6 and n 2 to 50: shares sum exactly to total, no two shares differ by more than one minor unit, result is identical across three runs and three platforms |
| Minor units | JPY (0), USD (2), and KWD (3) all round-trip through parse, store, and format with no loss |
| No floats | A static check fails the build if any `f32` or `f64` appears in the money path |
| Debt simplification | Post-simplification net position per member is identical to pre-simplification, per currency, and transfer count is at most n-1 |
| Cross-currency | Simplification never nets across currencies. A test asserts a group with INR and USD debts produces two separate settlement sets. |
| FX freezing | A transaction's converted value does not change when the rate table updates |
| Balance convergence | Three simulated devices applying operations in random order all reach byte-identical balances |
| Indian formatting | `1234567.89` in `en-IN` renders `₹12,34,567.89` |

The convergence test is the important one. It is the difference between believing
offline-first splitting works and knowing it does.

### 14.3 Adversarial suite

| # | Attack | Expected |
|---|---|---|
| A-01 | Server substitutes its own X25519 key for a member's | Safety phrase mismatch, member shows UNVERIFIED, warning surfaced |
| A-02 | Server replays an old key envelope after rotation | Rejected on epoch mismatch |
| A-03 | Removed member replays captured blobs | Cannot decrypt post-rotation records |
| A-04 | Record with a mutated header field | AAD binding fails, tag rejects, record dropped and surfaced |
| A-05 | Record replayed into a different group | AAD binds group ID, decryption fails |
| A-06 | Group member forges an expense as another author | Ed25519 signature fails, record rejected |
| A-07 | Malicious import file with 10^7 rows | Bounded, streamed, no OOM, dry run shows the count first |
| A-08 | CSV import with a formula injection payload (`=cmd\|...`) | Neutralised on import and on export; exported cells beginning `= + - @` are prefixed |
| A-09 | 10,000 OTP requests | Rate limited, exponential backoff, no enumeration |
| A-10 | Timing comparison of "account exists" vs "does not" | Difference below noise, both above the 250 ms floor |
| A-11 | Stolen refresh token replayed after rotation | Whole token family revoked, re-auth forced |
| A-12 | Device revoked, then attempts sync | Rejected, and it holds no current group key |
| A-13 | SMS parser fed a personal message | Never parsed, never stored, never logged |
| A-14 | SMS parser fed an OTP message | Dropped by the pre-filter before parsing |
| A-15 | Play build manifest scanned for SMS permissions | Zero matches, build fails if present |
| A-16 | App backgrounded during ledger view | Screenshot in the task switcher shows blur, not data |
| A-17 | Device backup extracted | Vault excluded from backup, keys not present |
| A-18 | Network capture across a full session | One destination host, no readable financial content |
| A-19 | Attempt to sum two `Money` values of different currencies | Compile error or a hard runtime rejection, never a silent result |
| A-20 | Escrow blob exfiltrated from the server | Useless without the passphrase; verified by attempting decryption with the blob alone |

### 14.4 The zero-knowledge proofs

Run at every release. Paste the raw output into the release notes.

1. **Network capture.** Proxy the app, log a distinctive expense
   (`CANARY-4F2A-HOTEL`, ₹99,999), grep every frame. Expected: zero hits.
2. **Server storage grep.** With the record synced, dump the database and object
   store, grep for the canary and the amount. Expected: zero hits.
3. **Local storage at rest.** With the app locked, pull the SQLCipher file from a
   test device and grep. Expected: zero hits.
4. **Backup audit.** Trigger an iCloud and a Google backup, inspect the payload
   for the vault. Expected: absent.
5. **Egress audit.** Full session with a proxy. Expected: exactly one host.
   Zero third-party requests.
6. **Manifest audit.** Play AAB merged manifest. Expected: no SMS permission.
7. **Deletion audit.** Delete the account, then query the store directly.
   Expected: zero rows across every table.

### 14.5 Functional, performance, and platform

- Multi-device E2E: 3 devices, offline edits, reconnect, converge. Automated.
- Import fidelity: a real Splitwise export imports with balances matching to the
  minor unit, asserted numerically.
- Cross-platform parity: the same operation log produces identical balances on
  iOS, Android, and web. Same core, but assert it anyway.
- Every performance budget in §10.4 has a test that fails the build when exceeded.
- Device matrix: a low-end Android from 2021, a current iPhone, an older iPad,
  Chrome, Safari, and Firefox.
- Accessibility: `axe` on web, Accessibility Scanner on Android, Accessibility
  Inspector on iOS, plus one manual screen-reader pass per platform.
- Localisation: `en-IN`, `en-US`, and one RTL pseudo-locale to catch layout
  assumptions early, even if RTL does not ship in v1.

### 14.6 Claims discipline

Every security sentence, in the app, the README, and both store listings, must
pass three checks before it ships:

1. Is it true given §2?
2. Would it still be true if the reader is A3, a malicious operator?
3. Does it state its own limit nearby?

**Safe to write:** "Your data is encrypted on your device before it is
uploaded." "We store ciphertext and cannot read it." "No ads, no trackers, no
subscriptions." "Export everything, any time."

**Never write:** "Bank-grade." "Military-grade." "Unhackable." "100% private."
"Fully anonymous." "GDPR compliant" or "DPDP compliant" as a badge. "Zero
knowledge" without the §2.4 caveat somewhere a reader will actually find it.

---

## 15. Master build checklist

Gates are real. Do not start a phase until the previous gate is signed off.

### Phase 0 · Decisions and skeleton

- [ ] Trademark search on SKIPTI in classes 9, 36, 42: USPTO, EUIPO, plain web
- [ ] Domain and store bundle IDs reserved
- [ ] Google Play Organisation registration started (long lead time, §12.1)
- [ ] Apple Developer Program enrolment
- [ ] ADRs written for every decision in §17
- [ ] `GUARANTEES.md`, `THREAT_MODEL.md`, `SECURITY.md`, `CONTRIBUTING.md` drafted
- [ ] Monorepo scaffold: `core/`, `ios/`, `android/`, `web/`, `server/`, `docs/`
- [ ] CI: build, test, clippy, `cargo audit`, `cargo deny`, `npm audit`, SBOM
- [ ] **GATE 0: no application code until the ADRs and `THREAT_MODEL.md` exist**

### Phase 1 · Core: money and splitting

- [ ] `Money` type: `bigint` minor units plus ISO 4217 code
- [ ] Full minor-unit exponent table (0, 2, and 3 decimal currencies)
- [ ] Lint rule: no `f32`/`f64` anywhere in the money path, build fails
- [ ] Lint rule: arithmetic on `Money` outside `norn` is a build failure
- [ ] Formatting per locale, including `en-IN` lakh/crore grouping
- [ ] All seven split types from §5.4
- [ ] Largest-remainder distribution sorted by stable UUID
- [ ] Production assertion: shares always sum to total
- [ ] Debt simplification, per currency, never cross-currency
- [ ] Saved group default splits (free, and paid elsewhere)
- [ ] FX rate frozen into every transaction with its timestamp
- [ ] Property tests from §14.2 all passing
- [ ] **GATE 1: three-device convergence test produces byte-identical balances**

### Phase 2 · Core: crypto and store

- [ ] Argon2id m=64 MiB t=3 p=1, RFC 9106 vectors pass
- [ ] HKDF hierarchy with all seven distinct info strings, RFC 5869 vectors pass
- [ ] AES-256-GCM with counter nonce and AAD binding, CAVP vectors pass
- [ ] **Negative tag test passes** (a tampered tag must fail)
- [ ] Ed25519 and X25519 vectors pass
- [ ] Group key generation, X25519 envelope wrap, per-device unwrap
- [ ] 6-word safety phrase derivation and QR verification flow
- [ ] Recovery Kit: 12 words, generated at setup, 3-word confirmation, no skip
- [ ] Escrow: MK wrapped by a passphrase-derived KEK, server holds an opaque blob
- [ ] SQLCipher store with the key held in the platform keystore
- [ ] Nonce uniqueness harness: 10^7 encryptions, zero collisions
- [ ] Zero `Math.random` or userland PRNG in the repo, enforced by lint
- [ ] **GATE 2: every KAT passes, and the crypto module imports nothing from UI**

### Phase 3 · Sync

- [ ] CRDT merge with a hybrid logical clock, field-level LWW registers
- [ ] Amount conflicts surfaced to the user, never auto-resolved
- [ ] Record header per §6.3, epoch field present from v1
- [ ] Sync by log index, not state vector
- [ ] 256-byte padding buckets
- [ ] Batch and coalesce, plus the optional randomised delay
- [ ] Attachment chunking at 256 KiB, lazy fetch
- [ ] Backends: none, own server, cloud drive, hosted, LAN QR
- [ ] Server: relays blobs only, **no function inspects record contents**
- [ ] Key rotation on member removal and device revocation, with re-wrap
- [ ] Offline-first verified: every action works with the network off
- [ ] **GATE 3: canary grep across network capture and server storage returns zero**

### Phase 4 · Identity

- [ ] Passkey / WebAuthn as the primary path
- [ ] Sign in with Apple, private relay supported (required by §12.2)
- [ ] Google Sign-In, minimum scopes
- [ ] Email OTP and phone OTP with rate limits and lockout
- [ ] **No password field anywhere in the product**
- [ ] Auth identity strictly separate from encryption identity, stated in onboarding
- [ ] Phone OTP cannot authorise device addition, rotation, or escrow unwrap
- [ ] Refresh token rotation with reuse detection and family revocation
- [ ] Device list as a first-class screen with safety phrases and Revoke
- [ ] Account deletion: 2 taps, export offered first, 7-day grace, then real
- [ ] Deletion verified by direct store query in a test, not by trusting the ORM
- [ ] **GATE 4: adversarial tests A-09 to A-12 and A-20 pass**

### Phase 5 · Ingestion

- [ ] CSV, OFX, QIF, MT940 importers with a remembered column mapping per bank
- [ ] Splitwise, Tricount, Settle Up, Splid importers, balances reconciled
- [ ] Import dry run, duplicate detection, whole-import rollback
- [ ] Formula-injection neutralised on both import and export
- [ ] Receipt OCR on device: Vision, ML Kit, Tesseract WASM. No image leaves.
- [ ] Share-sheet capture on iOS and Android
- [ ] Shortcuts action and Android intent published
- [ ] Quick-add widget on both platforms
- [ ] **Android Direct flavour only:** SMS parsing, on-device, sender-allowlisted,
      OTP pre-filter, forward-only, raw SMS discarded in the same call
- [ ] **Play flavour: SMS permission excluded at compile time**
- [ ] CI check greps the Play AAB merged manifest and fails on any SMS permission
- [ ] Honest in-app copy explaining the Play limitation, no "coming soon"
- [ ] **GATE 5: A-13 to A-15 pass; a Splitwise export imports to the minor unit**

### Phase 6 · Categories, analytics, export

- [ ] Two-level category tree, user-editable, archive not delete
- [ ] Merchant table (~5,000 patterns), signed static file, updatable
- [ ] Rules engine with ordering, history preview, and JSON export
- [ ] On-device classifier under 2 MB, retrains locally, **no cloud inference**
- [ ] All nine charts from §8.4, each with a table equivalent
- [ ] Every chart segment tappable through to its transactions
- [ ] Budgets with rollover; recurring detection that proposes, never inserts
- [ ] Subscription audit screen
- [ ] Export: XLSX, CSV, PDF, JSON, OFX, QIF, no caps, no watermark, one tap
- [ ] XLSX numbers are real numbers with currency formats, never strings
- [ ] PDF is vector, charts embedded as SVG, metadata stripped, neutral filename
- [ ] Unencrypted-export warning shown before writing
- [ ] Optional AES-256 encrypted ZIP (not legacy ZipCrypto)
- [ ] **GATE 6: 5,000 transactions export to XLSX and PDF in under 10 s**

### Phase 7 · Clients and design

- [ ] Rust core via UniFFI to iOS and Android, wasm-bindgen to web
- [ ] Native shells: SwiftUI, Compose, React
- [ ] Real Vardr Labs token values substituted for the §11.2 proposals
- [ ] **Light theme built first, dark derived from it**
- [ ] Money colours verified under deuteranopia and protanopia simulation
- [ ] Sign always printed, so colour is never the sole carrier
- [ ] Monospace tabular figures on every number, date, and currency code
- [ ] The balance line from §11.4 used consistently across home, group, and widget
- [ ] Motion budget respected; `prefers-reduced-motion` removes the digit roll
- [ ] No skeleton shimmer anywhere (data is local)
- [ ] Offline states are honest and never block
- [ ] All copy matches the §11.6 table
- [ ] Screenshot blocking, task-switcher blur, auto-lock, clipboard clearing
- [ ] Accessibility: contrast, 200% type, money announced correctly, focus rings
- [ ] Screen-reader pass on all three platforms
- [ ] Every §10.4 performance budget has a failing-build test
- [ ] **GATE 7: A-16 to A-18 pass; `axe` clean; 200% type does not clip**

### Phase 8 · Compliance and release

- [ ] Play: Organisation registration complete
- [ ] Play: Data Safety form matches the code, claim by claim
- [ ] Play: Financial features declaration submitted
- [ ] Apple: privacy nutrition labels accurate
- [ ] Apple: `PrivacyInfo.xcprivacy` complete
- [ ] Apple: `ITSAppUsesNonExemptEncryption` set correctly
- [ ] Apple: Sign in with Apple present and working with private relay
- [ ] Both: in-app account deletion, discoverable in two taps
- [ ] Privacy policy hosted, versioned, one page, honest
- [ ] Dependency audit: zero analytics, ad, attribution, or session-replay SDKs
- [ ] Web: CSP with no `unsafe-inline` on scripts, Trusted Types, SRI
- [ ] Reproducible builds verified on two machines, hashes match
- [ ] `cosign`-signed artifacts, public key published
- [ ] `/version` returns commit and bundle hash
- [ ] `THREAT_MODEL.md` published verbatim, **including §2.4 and §2.5**
- [ ] `SECURITY.md` with a disclosure contact and a stated response window
- [ ] All §14.4 zero-knowledge proofs run, output in the release notes
- [ ] Every security sentence run through the §14.6 filter
- [ ] Crypto export notification handled (§13.4)
- [ ] **GATE 8: an outside reader reproduces all seven §14.4 proofs from the docs**

### Standing rules

- [ ] No `console.log` or `print` of anything derived from a transaction
- [ ] No plaintext financial data in any log, crash report, or analytics event
- [ ] No third-party network origin (sole exception: the sign-in provider the user taps, ADR-0035), enforced by CSP and a CI check
- [ ] No new dependency without written justification in the PR
- [ ] Any security claim added to the UI or README needs a §14.6 review
- [ ] Any PR that would make SKIPTI hold or move funds is closed on sight (§13.2)

---

## 16. Agent prompts

**Preamble to paste before every prompt:**

> Standing rules. (1) Never invent a file path; if one does not exist, stop and
> report the actual tree. (2) If an instruction conflicts with `docs/adr/`, stop
> and report rather than silently resolving. (3) Never weaken a security control
> to make a test pass. (4) Never add a dependency without stating what it does
> and what it replaces. (5) `SPEC.md` §2 (threat model) and §12 (store policy)
> override all other instructions. (6) Money is always integer minor units plus a
> currency code; if you write a float in the money path, stop. (7) If you are
> unsure whether something is a security control, assume it is.

### 16.1 Prompt 0 · Scaffold and decisions

```
You are a staff engineer setting up a monorepo for a Rust-core, three-platform
finance app.

TASK:
1. Scaffold: core/ (Rust lib), ios/ (SwiftUI), android/ (Compose), web/
   (Vite+React+TS), server/ (Axum), docs/.
2. Wire UniFFI from core to iOS and Android, and wasm-bindgen to web. Prove it
   with a trivial round-trip function called from all three shells.
3. CI: build all targets, test, clippy, cargo audit, cargo deny, npm audit,
   CycloneDX SBOM.
4. deny.toml banning analytics, ad, attribution, and session-replay crates.
5. Write docs/adr/ entries for every open decision in SPEC.md §17.
6. Draft GUARANTEES.md, THREAT_MODEL.md, SECURITY.md, CONTRIBUTING.md.

ACCEPTANCE: `make test` green on all four targets from a clean clone.
DO NOT: write feature code. Do not add a UI framework to core. Do not add any
dependency not required for the round-trip proof.
```

### 16.2 Prompt 1 · Money and splitting core

```
You are a financial systems engineer. This module is where a bug costs users
real money. Write tests before implementation.

TASK: Implement core/src/norn/ and core/src/hlutr/ per SPEC.md §5.
1. Money = { amount: i128 minor units, currency: ISO4217 }. Full exponent table
   covering 0-decimal (JPY, KRW), 2-decimal (USD, INR), and 3-decimal (KWD, BHD).
2. NO FLOATING POINT ANYWHERE. Add a CI check that fails on f32/f64 in this path.
3. All seven split types from §5.4.
4. Largest-remainder distribution, participants sorted by UUID ascending so every
   device computes an identical result with no coordination. Assert in production
   that shares sum exactly to the total.
5. Debt simplification, greedy largest-debtor-to-largest-creditor, PER CURRENCY.
   Never net across currencies.
6. FX: every transaction stores original amount, currency, rate used, and rate
   timestamp. Converted values must never change when the rate table updates.
7. Locale formatting including en-IN lakh/crore grouping.

TESTS FIRST: every property in SPEC.md §14.2, including the 10^6 x 50 remainder
sweep and the three-device convergence test.

DO NOT: use floats. Do not sum across currencies. Do not sort participants by
name or join order. Do not round at the display layer to hide an arithmetic bug.
```

### 16.3 Prompt 2 · Crypto and encrypted store

```
You are a cryptographic engineer. Build core/src/lykill/ and core/src/skrin/
standalone: no UI, no network.

TASK per SPEC.md §3.
1. Argon2id m=65536 KiB, t=3, p=1, 32-byte output.
2. HKDF hierarchy with info strings exactly: "skipti/v1/personal",
   "skipti/v1/groupwrap", "skipti/v1/auth", "skipti/v1/escrow",
   "skipti/v1/search", "skipti/v1/backup", "skipti/v1/fingerprint".
3. AES-256-GCM. Nonce = 4-byte random session id || 8-byte BE counter. AAD binds
   version, recordType, groupId, epoch, authorDeviceId, and counter.
4. Group keys: random 32B, wrapped per recipient device via X25519 ECDH.
5. 6-word safety phrases from a fixed 2048-word list, for out-of-band verification.
6. Recovery Kit: 12 words, 132 bits, BIP-39 wordlist WITH a docs note that this
   is not a cryptocurrency wallet.
7. Escrow: MK wrapped by a passphrase-derived KEK. The server stores an opaque
   blob and can never open it.
8. SQLCipher store with the key held in the platform keystore, exposed to the
   shells through a trait so iOS and Android supply their own keystore backing.

TESTS: RFC 9106, RFC 5869, NIST CAVP (including a MUST-FAIL tag case), RFC 8032,
RFC 7748. Plus a 10^7-encryption nonce-uniqueness harness.

DO NOT: implement password login, a server-openable escrow, a password reset, or
any key that the operator could reconstruct. Do not use Math.random or a
userland PRNG. Do not lower Argon2 parameters for test speed; use a separate
reduced fixture and document why.
```

### 16.4 Prompt 3 · Sync and server

```
You are a distributed systems engineer.

TASK: core/src/straumr/ plus server/.
1. CRDT: signed immutable operations for the ledger, hybrid logical clock,
   field-level LWW registers for edits. AMOUNT conflicts are surfaced to the user,
   never auto-resolved.
2. Record header per §6.3 including the epoch field, which ships in v1 even though
   rotation lands in v1.1.
3. Sync by log index, not Yjs-style state vector. Pad to 256-byte buckets.
4. Backends behind one trait: none, own server, cloud drive (iCloud/Drive/Dropbox
   document APIs), hosted, and LAN QR.
5. Attachments chunked at 256 KiB, per-chunk keys wrapped by the record key,
   fetched lazily.
6. Server (Axum + Postgres + object storage): stores and relays opaque blobs and
   envelopes. It computes no balances, resolves no conflicts, and parses no
   record contents.

ACCEPTANCE: three simulated devices, offline edits in random order, converge to
byte-identical balances. Canary grep across network capture and server storage
returns zero hits.

DO NOT: write any server function that inspects record contents. If you find
yourself needing to, stop and report: the design has failed.
```

### 16.5 Prompt 4 · Ingestion, including the SMS split

```
You are a mobile platform engineer. Read SPEC.md §7 fully first, including why
the Play build cannot contain SMS permissions.

TASK:
1. Importers: CSV, OFX, QIF, MT940, plus Splitwise, Tricount, Settle Up, Splid.
   Column-mapping UI that remembers layouts per bank. Handle Indian bank date
   formats and the Dr/Cr column convention. Dry run, duplicate detection by
   amount+date+merchant, and whole-import rollback as one unit.
2. Neutralise CSV formula injection on BOTH import and export: any cell starting
   =, +, -, or @ gets prefixed on write and is never evaluated on read.
3. Receipt OCR on device only: Vision (iOS), ML Kit (Android), Tesseract WASM
   (web). No image ever leaves the device.
4. Share-sheet capture on iOS and Android that parses pasted or shared payment
   confirmation text.
5. Shortcuts action (iOS) and a documented intent (Android) for quick-add.
6. Quick-add widget on both platforms.
7. ANDROID PRODUCT FLAVOURS:
   - "play": the SMS module is EXCLUDED AT COMPILE TIME. No SMS permission in the
     manifest, not even placeholder text, which is itself a policy violation.
   - "direct": SMS parsing, opt-in, on-device only, sender allowlist, OTP
     pre-filter runs FIRST, forward-only from grant time with no inbox backfill,
     raw SMS discarded within the same function call before any write.
8. CI: grep the built Play AAB's merged manifest for SMS permissions and FAIL the
   build on any match.
9. Honest in-app copy in the Play build explaining the limitation with a link to
   the direct download. Not "coming soon".

DO NOT: read SMS on the Play flavour under any circumstance. Do not backfill
inbox history. Do not send an SMS body, fragment, or hash off-device for any
reason including debugging. Do not attempt an iOS SMS reading path; none exists.
```

### 16.6 Prompt 5 · Analytics and export

```
You are a data and reporting engineer.

TASK:
1. Two-level category tree per §8.1, user-editable, archive rather than delete.
2. Merchant normalisation table (~5,000 patterns) as a signed static file.
3. Rules engine: ordered, previewable against history before saving, JSON
   exportable.
4. On-device classifier under 2 MB (TFLite / Core ML) over merchant string,
   amount band, and time of day. Retrains locally, nightly, on charge.
5. All nine charts from §8.4. Every chart has a table equivalent one tap away and
   every segment drills through to its transactions.
6. Budgets with optional rollover. Recurring detection that PROPOSES, never
   auto-inserts. Subscription audit screen.
7. Export: XLSX (multi-sheet, real number cells with currency formats, never
   strings), CSV (RFC 4180, UTF-8 BOM), PDF (vector, SVG charts embedded,
   metadata stripped, neutral filename), JSON (lossless, schema-versioned), OFX,
   QIF. No caps, no watermarks, no delays, one tap.
8. Unencrypted-export warning before writing. Optional AES-256 encrypted ZIP.

DO NOT: send any transaction data to a cloud model or inference API. Do not cap,
watermark, or gate export. Do not rasterise charts into the PDF.
```

### 16.7 Prompt 6 · Design system and shells

```
You are the design lead. Read SPEC.md §11 fully.

CONTEXT: House language from vardrlabs.com: dark technical surface, hairline
rules as the primary structural device, uppercase letterspaced micro-labels with
middot separators, monospace tabular figures for EVERY number, terse declarative
copy with no marketing register.

TASK:
1. Token system per §11.2. BUILD THE LIGHT THEME FIRST and derive dark from it. A
   finance app is used outdoors and one-handed; dark-only is a posture, not a
   design.
2. Money colours are a warm rust and a cool teal-green, never red/green, and the
   SIGN IS ALWAYS PRINTED so colour is never the only carrier. Verify under
   deuteranopia and protanopia simulation.
3. The balance line from §11.4, used identically on home, group, and widget.
4. Motion budget from §11.5. The digit roll is the one indulgence. No skeleton
   shimmer: the data is local.
5. Every string from the §11.6 copy table, verbatim.
6. Accessibility floor from §11.7, including correct screen-reader announcement
   of currency amounts, which needs explicit labels because both platforms read
   formatted currency strings wrongly by default.

DO NOT: invent hex values and present them as the site's; flag which tokens still
need real values. Do not use a cross-platform UI framework. Do not add gradients,
glass blur, or a second animated element.
```

### 16.8 Prompt 7 · Compliance and release

```
You are a release engineer preparing three store submissions.

TASK per SPEC.md §12 and §14.4.
1. Play: Organisation registration, Data Safety form matching the code claim by
   claim, Financial features declaration, current target API level, in-app
   account deletion plus a web URL.
2. Apple: Sign in with Apple with private relay, privacy nutrition labels,
   PrivacyInfo.xcprivacy, ITSAppUsesNonExemptEncryption, in-app account deletion.
3. Web: CSP with no unsafe-inline on script-src and no unsafe-eval, Trusted
   Types, SRI on every asset including same-origin.
4. Reproducible builds: pinned toolchains, SOURCE_DATE_EPOCH, verified by
   building on two machines and comparing hashes. cosign-sign artifacts. /version
   returns commit and bundle hash.
5. Dependency audit proving zero analytics, ad, attribution, or session-replay
   SDKs on any platform.
6. Publish THREAT_MODEL.md verbatim INCLUDING §2.4 (a malicious operator can
   serve modified web JS) and §2.5 (a group member sees the group). Write
   SECURITY.md with a disclosure contact and a stated response window.
7. Run all seven §14.4 zero-knowledge proofs and paste the raw output into the
   release notes.
8. Review every user-facing security sentence against §14.6.

ACCEPTANCE: an outside reader reproduces all seven proofs from the published docs
and gets the same results.

DO NOT: soften or omit §2.4 or §2.5. Do not write any claim from the §14.6 banned
list. Do not ship a build whose hash does not match the published one.
```

---

## 17. Open decisions

### 17.1 CRDT library (blocks Phase 3)

Automerge is mature with a good Rust core and a well-understood document model.
Loro is faster and more memory-efficient with a modern API but is younger.
**Recommendation: Automerge for v1.** Yjs is optimised for text sequences and is
the wrong shape for a ledger of records.

### 17.2 Which sync backend is the documented default (blocks Phase 3)

Cloud-drive sync is the most defensible: zero server cost forever, zero operator
trust, and the best privacy story in the segment. It is also the fiddliest to
build across three platforms. Own-server is easiest to build and hardest to
adopt. **Recommendation: build own-server first for correctness, then
cloud-drive, and document cloud-drive as the default in the README.**

### 17.3 Does SKIPTI ship an SMS build at all (blocks Phase 5)

The Direct flavour is real work and reaches a small audience. §7.4 covers most of
the value with none of the risk. **Recommendation: ship v1 without SMS entirely,
and treat the Direct flavour as a v1.1 decision made with real usage data.** If
you build it anyway, build it last, so a policy problem cannot block the launch.

### 17.4 Personal finance and splitting in one app, or two (no phase, decide early)

Doing both is the differentiator, and it is also two products' worth of surface
area for one person. The alternative is shipping splitting first, since it has
the clearer wedge against Splitwise's caps, then adding personal tracking once
there are users. **Recommendation: ship splitting first**, with the personal
ledger architecturally present from day one so it is not a retrofit.

### 17.5 Hosted instance, or self-host only (blocks Phase 8)

Hosting makes you the A3 adversary in your own threat model and puts an abuse and
subpoena surface under whatever entity ships it. Self-host only sidesteps that
and costs you every casual user. **Recommendation: self-host plus cloud-drive
first, and if a hosted instance ever launches, publish its costs and fund it by
donation so the incentive to monetise data never appears.**

### 17.6 What happens if it gets popular

Write the answer down now, while it is hypothetical and you can be honest.
Donations, a paid hosted tier that changes nothing about the app, or a one-time
purchase on the stores are all compatible with §1.3. Ads, data sale, and gating
the core loop are not, and the guarantee file exists so that a future version of
you cannot quietly reverse the decision.

---

## 18. Appendices

### 18.1 Glossary

| Term | Meaning here |
|---|---|
| AAD | Additional authenticated data. Bound into the tag, not encrypted. |
| CRDT | Conflict-free replicated data type. Merges without a server. |
| DEK / KEK | Data encryption key, and the key that wraps it. |
| Envelope | A group key encrypted for one specific recipient device. |
| Epoch | A keying generation, incremented on rotation. |
| Escrow blob | The master key wrapped by a passphrase-derived key, stored server-side and unopenable by the server. |
| HLC | Hybrid logical clock, for ordering across devices with skewed clocks. |
| KAT | Known-answer test against a published standard vector. |
| Minor unit | The smallest denomination: paise, cents, and for JPY, the yen itself. |
| Recovery Kit | 12 words, the only path back in without a device or passphrase. |
| Safety phrase | 6 words derived from a device key, for out-of-band verification. |
| Settlement | A ledger record that a debt was paid. It never moves money. |

### 18.2 ADRs to write in Phase 0

```
0001  Rust core with native shells, and why not Flutter or React Native
0002  Money as integer minor units, never floating point
0003  Local-first with pluggable sync backends
0004  CRDT library selection
0005  Key hierarchy, HKDF domain separation, and group envelope wrapping
0006  Recovery Kit instead of password reset
0007  Passwordless identity: passkeys and OTP only
0008  SKIPTI records debts and never moves money
0009  MIT rather than AGPL, and the App Store reasoning
0010  Two Android flavours, and the SMS policy position
0011  Light theme first, and the money colour pair
0012  No analytics, ads, attribution, or session replay, ever
0013  Product guarantees, and what would have to be true to change one
0014  Not becoming a default SMS handler, and why the Truecaller path is refused
0015  Notification listener before SMS parsing
0016  Sensitive permissions ship in an update, never in a first submission
0017  Capture produces proposals in a review inbox, never auto-committed entries
```

### 18.3 Definition of done, per module

1. It has tests, and at least one is a negative test.
2. It has no `TODO` marking a security control.
3. Every secret it touches is wrapped, wiped, or non-extractable.
4. It logs nothing derived from a transaction.
5. Its section of §15 is fully ticked.
6. Its user-facing copy has passed the §14.6 filter.

### 18.4 If you only do six things from this document

Ranked by damage prevented per hour spent.

1. **Integer minor units, never floats** (§5.1). Floating point in a money path
   is a bug you will chase for a year and users will never forgive.
2. **Deterministic remainder distribution sorted by UUID** (§5.5). This is what
   makes offline-first splitting possible at all; get it wrong and two phones
   disagree about money.
3. **Verify peer keys out of band** (§3.2). Without safety phrases, the ECDH is
   theatre against exactly the adversary E2EE is supposed to stop.
4. **Never move money** (§5.8). This one boundary is the difference between a
   side project and a regulated financial institution.
5. **Assume the SMS feature is rejected** (§7.2). On-device processing does not
   change this, because eligibility turns on what kind of app you are, not on
   what you do with the data. Plan for it up front; discovering it at submission
   costs you a launch.
6. **Publish §2.4 and §2.5 verbatim** (§14.6). The credibility of every other
   claim rests on being the kind of project that says the uncomfortable parts out
   loud.

One rule spanning several of the above: **the moment SKIPTI holds funds, reads a
whole SMS inbox, or gates the core loop, it becomes an ordinary app with an
unusual README.** Each of those is a convenience that cancels a promise the rest
of this document spent two thousand lines earning.

---

*SKIPTI · MIT · Web, iOS, Android*
*No subscriptions. No ads. No trackers. Your ledger, your keys, your device.*
