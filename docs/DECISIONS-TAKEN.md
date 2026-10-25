# Decisions taken for you, sessions 5 and 6

Every call the controller made while you were away, decisions 25 to 128 (119 to 126 are your own, listed so the D-235 chain reads whole), ranked by what breaks if it is wrong.
To overturn one, say it in one line in chat: "overturn 31", or "overturn 36: creator wins".

Already confirmed by you (pop-up, 2026-10-17): 19 to 24. Not listed again.

**Session 7 (2026-10-18, Fable), re-decided with safety first and answered by you (pop-up):**
- **31 CHANGED:** the record below is wrong on one point: the emulator ran on the host GPU (`-gpu host`, Apple M4 GLES
  translator), not in software, and frames delivered and deadlines missed did not change; GPU time at p90 rose 18 to
  25 ms. The cost is each card re-recording the one shared blurred art layer under its own moving clip, so the blur runs
  once per card per scroll frame; freezing the art (b) and the lower animation mode (c) cannot fix it. You chose option
  (d): bake the shared blur once per art frame into one quarter-size layer the cards sample, A/B with the D-226 runner,
  shipped only if it measures and looks identical. The real-phone check stays (`.drafts/s7/decision-31-report.md`).
- **36, 37 CONFIRMED.** New evidence: the alternative for 36 reopens the unremovable-member attack in a timing variant.
  New residue (no security loss): evicting an intruder from the creator place costs the creator the creator path for
  good; 0.5.1 fix: the removal record carries the removed uid, and only the creator's own removal counts.
- **44 to 52 CONFIRMED.** New cost recorded under G-08: lifting a 44 denial removes the forger's member, which will be a
  placeholder with real balances. **The G-08 unit (server-verified claim author and order carried into core) is built
  BEFORE 0.5.0**, your call. Findings: `.drafts/s7/redecide.md`.

**Session 8 (2026-10-19, Opus as controller):** D-230, the G-08 unit you asked for before 0.5.0 (held places: the
server says who holds a place), is finished; its fifteen calls are **53 to 67**, under "Critical" as the D-230 chain.
53 to 64 were taken in session 7 while it was built. The whole-branch security review then found a takeover the unit
had made possible (a copied id that reached a mark took its owner's records) and a way to block marks with a phone
clock: **66** (the first account to mark an id keeps it) turned the takeover back into a denial, and **54** was reworded
(the server stamps a mark's time; no phone clock plays a part). **65** (a new install's first join carries its mark) was
a residue the review called a decision. **67** is the look's "Unknown member's ... moved" line on a rejoiner's own
phone: a D-210 bug, fixed in this unit, shells only. What they leave (G-08's unmarked places, G-31 (3)'s races) stays
for the Fable security audit. D-230's Functions changes are not deployed, like D-229's.

**Session 8, D-231 (2026-10-19, Opus as controller):** Less motion, the lower animation mode you asked for, is
finished; its five calls are **68 to 72**, under "High" as the D-231 chain. You chose the summary yourself by pop-up
("Still art, fewer animations"). The whole-branch review found Android's tab bar ignored the switch (fixed in the unit)
and that the low-RAM line catches phones sold with 3 GB (**70**, corrected and kept). No cloud change.

**Session 8, D-232 (2026-10-19, Opus as controller):** Aurora dark as the default for a new install, your call of
2026-10-18, is finished; it is **73**, listed with the five calls it needed, **74 to 78**, under "High" as the D-232
chain (73 is yours, like 42: here so the chain reads whole). An install that ran before keeps following the phone
(**74**). The launch screen, drawn before the app runs, follows the phone (**77**) until, on Android 12 and later, the
app has told the system its own choice (**78**); on iPhone a new install on a light phone shows a light launch screen
while it starts. On the way, the Android app lock stopped asking again on every rotation and theme change; one commit of
that fix let the lock open without asking, caught by review and fixed before anything merged (BUILD-LOG incident 30).
No cloud change.

**Session 8, D-234 (2026-10-19, Opus as controller):** the twin-fix unit is finished: your two asks (the text-size
sample line in US dollars, App icon as one row that opens in place), a real width test for every settings summary, and
the fourteen findings the looks of D-228 to D-232 left. Its calls are **80 to 102**, under "Medium" as the D-234 chain.
Two of them are words you may want to change: **83** (Haptics reads "A tap when it is done") and **84** (You's account
row reads "Google · 2 phones"). Four findings move to 0.5.1, each too big for a twin fix (**86**, **89**, **93**,
**98**). Two are look-setup artefacts, not defects (**85**); two are width differences no layout can remove (**90**'s
width half, **96**). Android phones now run portrait, as the iPhone does (**88**), and nine Android cards take the iPhone's inner spacing
(**102**, a difference the final review found outside the list). No cloud change.

**Session 8, D-235 (2026-10-19, Opus as controller):** the Opus pre-release audit you asked for ("a full opus audit of
everything including bugs and security, before the Fable part") is finished, with its fixes. Five auditors found 1
Critical, 9 High, 15 Medium and 24 Low: the Critical was that a group expense's money edit had never reached the other
phones since D-195; it is fixed and was seen fixed on devices, the lost edits arriving after the update. Its calls are
**103 to 128**, under "Critical" as the D-235 chain. **119 to 126 are your own calls** by pop-up, listed so the chain
reads whole. **127** was taken and reverted by **128** the same hour. Everything in Firebase is merged, NOT deployed:
deploy the two new indexes first and wait until both are READY (**126**). An index race put one core file into another
wave's commit (BUILD-LOG incident 31; content right, message wrong, history kept).

**Session 9, D-237 (2026-10-19, Fable):** your Phase A, the pre-release security audit, is done: every open G-item,
the D-235 leads (a) to (j) and the "for Fable" list re-read from the code, the deployed rules diffed against the
repo's and the new ones checked against what 0.4.0 phones write. Three fixes before the deploy, its calls **131 to
134** under "Critical" as the D-237 chain: a place claimed in one group no longer reads as you in another (a co-member
could make a co-member's history yours on your phone with one invite), each record stamp takes a random step (a
co-member could pre-empt your edits for a day by listing your next ids), and a participant id belongs to the account
that registered it first (one copied profile stripped a person of D-230's marks). The rest is recorded, not fixed
(134). No new index, no rules change; the Functions part deploys with D-235's.

42 is your own rule, not a controller call: the dash rule covers the public side only; the repo gets a whole-repo sweep
before it goes public. Listed here only because 41 depends on it.

Status words: **merged** = on main, ships with 0.5.0, nothing released. **Not deployed** = Cloud Functions on main,
deploy at 0.5.0 (Functions before apps). D-229 (44 to 52) is merged too; its Functions changes are not deployed.

---

## Look at these first

### 31 (D-226): Android Aurora scrolls about 4.5 ms slower, accepted for now

- **What:** Android's Aurora scroll frame time stays about +4.5 ms at the 90th percentile over the pre-D-226 build
  (28 ms vs 23.5 on the emulator). A real Android phone decides at the 0.5.0 release gate whether a unit is owed.
- **Why:** two fixes were built and measured. A separate blurred copy of the art at 15 fps (and 7.5) bought nothing
  (28 ms vs 26.5 before it) and was reverted. The cost is each card drawing the blurred art during a scroll, not the
  blur pass (`.superpowers/sdd/d226/fix2-report.md`). The emulator draws in software, so its number is not a phone's.
- **Risk if wrong:** Aurora, the default theme, stutters on mid and low-end Android while scrolling.
- **Overturn:** pick one of the two options left: (a) each card draws its art from a snapshot bitmap (costs memory per
  card), or (b) freeze the card art while a scroll is in flight (the art stops moving under your finger). Either is an
  Android-only unit, small to medium. Your "lower animation mode" idea (a toggle built on D-214's still frame) is a
  third route and sidesteps both.
- **Status:** merged (d581626). Owed: frame time on a real Android, and your look at the before/after pairs
  (`.drafts/d226/look/pairs/`, `.drafts/d226/relook/pairs/`).

### 36 (D-227): on the server, an accepted invite beats the creator's claim to a place

- **What:** when the creator's place (from the creator's first create op) and another member's accepted invite name
  the same place, the invite wins.
- **Why:** the Opus security review (`.superpowers/sdd/d227/task-1-review.md`, Important 1) found nothing forces the
  creator's first create to land before anyone joins. A modified client could invite a place, let someone join it,
  then write its create naming that place first. The place turned contested and both accounts became unremovable:
  two accounts with read access nobody could revoke. An accepted invite is written by the server, a create op by a
  client, so the server's evidence decides. Re-review: Approved, 0 Critical, 0 Important; the attack's test goes red
  if 36 is undone (`task-1-fix-review.md`).
- **Risk if wrong:** an honest creator who shares an invite before its group reaches the cloud, and someone accepts in
  that window, loses its place (keeps every uid-based owner power). Narrow; before the fix the same race left both
  stuck. Residue: a creator that holds no place cannot be removed after it hands the group over (only deleteGroup ends
  its access).
- **Overturn:** "creator wins" is a one-line change in `holderIn`, but it reopens the unremovable-members attack.
  Safer alternative the reviewer offered: the creator wins only when its create's `createdAt` predates the invite's
  `usedAt`. Small Functions change plus emulator tests and sabotage rows.
- **Status:** not deployed (merged 87da0e7, Functions ship at 0.5.0).

### 37 (D-227): a removed creator never gets its place back through the creator path

- **What:** the creator holds its place through the creator path only while no removal record ever named that place.
  A removed creator who comes back holds a place through an invite, like anyone else.
- **Why:** otherwise any member writing `addMember` for the old place could hand the creator's powers back. The
  re-review checked who can produce a removal record (`removeMember`, `adoptRemovals`) and found no bypass; a later
  `addMember` is ignored (sabotage G2 red on exactly that test).
- **Risk if wrong:** a creator removed by mistake cannot slip back in as "creator"; it needs a fresh invite. That is
  the intended cost. If the removal record logic ever accepted a forged record, a creator could be locked out of the
  creator path, but phones cannot write `removal: true` (the rules refuse it).
- **Overturn:** drop the `everRemoved` check in `creatorHolder`. Small code, but reopens the hand-back. Not
  recommended.
- **Status:** not deployed (merged 87da0e7).

---

## Critical: money or identity could read wrong, or a security control

Ranked here, in full above: **36, 37**.

### The D-229 chain, 44 to 52: four review rounds on who an id belongs to

The question: in a group, which member does a "you" id (from claims and aliases) read as. Get it wrong and one
person's payments read as another's. It took four Opus review rounds after the first review, each finding a new hole
in the previous fix. Reviews in `.superpowers/sdd/d229/task-1-*review.md` and `task-2-*review.md`.

**Where it ended (round 4, Approved, 0 Critical, 0 Important; then the whole-branch review):** a person's history
can be taken only by a claim with a **backdated record identity**, or by a member who already knows the id (from a
shared group) claiming it with an honest timestamp BEFORE that person joins the group (the whole-branch review's I2;
D-227's invite checks and 47 narrow it). Three backdated routes: once the forger's member is removed, when written on an already
removed member, or on a present member once the victim leaves. Every other forged shape the reviewer tried gives the
right owner or a denial.

**Still open for the security audit:**
- **CLOUD-SECURITY G-08** (claims can be contested): the backdated claim. Record identity is phone-chosen; the rules
  check only `createdAt`, which core does not order by. A fix would order claims by the server's `createdAt`.
- **CLOUD-SECURITY G-31** (`shareAliases`): (1) a co-member can stage a permanent denial by copying an unaliased id
  into their own log; (2) a side-effect-free probe of whether another member's log holds an id; (3) ids no account owns
  on the server yet (a second phone's adopted "you", a purged account re-registering) are covered only by core's
  layer; two accounts on one phone get the repair refused for good (fails closed); (4) read cost: is 100 a day right
  once real logs are measured.

Status for all nine: **merged** (core, Functions and both phones); the Functions part is **not deployed**.

#### 44: two present claims on one id give no alias

- **What:** when two or more claims on present members name the same "you" id, that id gets no alias in that group.
  The person's records read as an unknown member, never as someone else's.
- **Why:** the first review found a member could claim an earlier, unclaimed member with a joiner's id and take the
  joiner's records. A denial is recoverable; a takeover moves money between people.
- **Risk if wrong:** any member can freeze another's history to "unknown member" with one forged claim, until the
  forger's member is removed (49, 50).
- **Overturn:** back to "first claim wins". Small core change, reopens the takeover. Needs a new review round.

#### 45: a group whose place is taken turns read-only on that phone

- **What:** when an account's place in a group is held or contested by another account's claim (36's race, or any of
  its ids claimed by someone else), the group turns read-only on that phone with a plain explanation.
- **Why:** otherwise the phone keeps writing records that read as the other person's money (review Important 2). It
  can only happen in a narrow offline race or under attack.
- **Risk if wrong:** a person stuck read-only in a group with no button to fix it; the way out is the owner removing
  the forger's member. Worth seeing the explanation copy on a phone.
- **Overturn:** let the phone write anyway (misattributed money) or add a third core state with a recovery flow.
  Medium: core plus both shells.

#### 46: the server counts former members' logs when checking an alias

- **What:** `shareAliases` treats an id as contested when any current or former member's own log holds it (the plan
  said current only).
- **Why:** otherwise a member who stayed could copy a removed person's old id and take that person's records.
- **Risk if wrong:** only extra refusals of honest repairs, never a takeover.
- **Overturn:** current members only. One-line Functions change; reopens the copy of a leaver's id.

#### 47: an alias can never name another account's id

- **What:** the server refuses an alias for any other account's participant id (member or not), and each phone ignores
  a marked alias that a claim on another member contests.
- **Why:** the `shareAliases` review found a Critical: a member could pre-alias a future member's id onto their own
  place, so that person's payments read as the attacker's, hidden from the victim. Two layers because each alone has
  a gap (the server cannot see a second phone's adopted id; a phone cannot tell who wrote a claim).
- **Risk if wrong:** honest denials: two accounts on one phone get the repair refused permanently (fails closed).
- **Overturn:** drop either layer. Small code each; reopens the pre-alias takeover. Not recommended.

#### 48: a daily quota on the alias repair

- **What:** `shareAliases` is charged against a daily quota (100 alias records a day per account), reads the caller's
  own log first, and each member's log once per call.
- **Why:** the review measured one unbounded call at about 75,000 reads (8 members, modest logs): over the free daily
  allowance in one call, on your $5 budget.
- **Risk if wrong:** too low, honest repairs fail until tomorrow and records stay "unknown member" a day; too high,
  billing exposure. Many accounts multiply it (G-23, App Check G-05).
- **Overturn:** change the number in `people.js`. Tiny.

#### 49: the contest in 44 counts present members only

- **What:** 44's contest counts only claims on members still in the group; otherwise the id follows the claim rule.
- **Why:** round 1 found a Critical: an honest person removed and invited back has an old claim and a new one naming
  the same id, and 44 as first built made their whole history unknown and their group read-only. With 49 their history
  stays on their old member and new records go to the new place.
- **Risk if wrong:** a forged claim on a removed member could still count; 50 and 51 close that.
- **Overturn:** count removed members too. Breaks every honest rejoin. Not recommended.

#### 50: a removed member's claim counts only if it came first

- **What:** a claim on a member who has left counts toward an id's alias only if it was written before every claim on
  a current member naming the same id.
- **Why:** round 2 found a forger could claim with a victim's id, then have its own member removed, crediting the
  victim's payments to the forger. An honest rejoin's old claim is always older than its new one.
- **Risk if wrong:** the backdated claim (G-08) gets through.
- **Overturn:** drop the age check. Reopens the remove-to-steal route.

#### 51: an id goes to its earliest-written counting claim, not member order

- **What:** the alias goes to the earliest-written claim that still counts, never by the order members were added.
- **Why:** round 3 found that once nobody present claims an id (the person left, or between removal and rejoin),
  member order let a later forger take the history with no backdating. The earliest claim is the person's own; in
  honest data it is the same member as before.
- **Risk if wrong:** none found in honest data (341 core lib tests, every older golden unchanged). Backdating still wins.
- **Overturn:** back to member order. Small core change; reopens round 3's hole.

#### 52: the server refuses a second invite into a group you are already in

- **What:** `acceptInvite` refuses someone who already holds a place in that group ("You're already in this group." on
  both phones), before any write, and the invite stays unused. Re-accepting the invite for your own place still works;
  a removed member invited back is not refused. A lost-place group also hides "Remove from this phone" (you are still
  in it), which reversed an earlier call to keep it.
- **Why:** the whole-branch review found one person tapping a second invite into a group they were in made their two
  claims contest each other (44): their history read as unknown and their own phones locked the group.
- **Risk if wrong:** someone who genuinely wants two places in one group cannot have them (no product case found).
- **Overturn:** drop the refusal and teach core to merge one account's two claims. Medium: server, core and phones.

### The D-230 chain, 53 to 67: the server says who holds a place

The question D-229 left open (CLOUD-SECURITY G-08): claims are written by phones and ordered by a record identity the
phone chooses, so a claim written before a person's own could deny them their group or, later, take their history. You
asked for the fix before 0.5.0 (session 7). D-230 has the server mark each place an account holds (`heldPlace`, marked
`held: true`, which no phone can write), and core believes the mark over any claim. It took two core review rounds, six
Functions rounds after the first review, and a whole-branch security review with its confirmation, each finding an
honest flow the previous fix broke or a route it left open. Reviews in `.superpowers/sdd/d230/` (task-1-review.md,
task-1-fix-review.md, task-2-review.md, final-review.md with its Confirmation), amendments A1 to A10 in
`amendments.md`.

**Where it ended (the confirmation: Approved, 0 Critical, 0 Important):** a marked place cannot be taken or denied by
anything a phone writes (a claim, an alias, an `addMember`), and no phone clock plays a part in a mark. An id copied
past the server's checks is a denial, never a takeover (66). What stays is what has no mark yet (members from before
0.5.0 until their phone's next push, placeholders nobody holds, a member whose join-time mark failed until the next
push) and one race: a second phone that joins before its first pull, whose new id a watching co-member can copy within
seconds (the confirmation's F2). Recorded in G-08 and G-31.

**Still open for the security audit:**
- **CLOUD-SECURITY G-08**: the unmarked places above, a backdated `addMember` reordering one account's own two places
  (the rejoiner's history moves to its new place and the redistribution counts twice), and an `addMember` of a person's
  id before their mark denying it for good.
- **CLOUD-SECURITY G-31 (3)**: the second-phone race (F2), and an account switch on one phone, whose second account's
  join is refused while the first's marked place is present (F3, fails closed).
- The `places` quota (55) against real logs, with 48.

Status for all fifteen: **merged** (core, Functions and both phones); the Functions part is **not deployed**.

#### 53: a server-marked held place is final for its member and its id

- **What:** a `heldPlace {group, member, you}` written by the server with the mark is final: every unmarked claim on
  that member with another id, or naming that id on another member, is void (listed as contested), and decisions 44 to
  51 run over what is left. Per member the latest mark stands.
- **Why:** only the server knows which account holds a place (the invites) and whose an id is (participant id, own
  log). A phone-written claim carries neither, which is the whole of G-08.
- **Risk if wrong:** a wrong mark would be believed by every phone; the server's checks (54, 58, 60, 64, 66) are what
  stand between a modified phone and a mark. A place nobody marked keeps the old rules.
- **Overturn:** drop the mark's precedence in core (back to D-229's claims). Medium; reopens G-08.
- **Status:** merged; Functions not deployed.

#### 54: the server stamps a mark's time; the phone's clock plays no part

- **What:** both writers give a held mark a record identity of their own: the server's clock, a counter that is the
  record's index in its call, and a random device; the document id follows from it. The phone's clock and record id
  play no part (the answer still lists the phone's own record ids, so the phones need no change). Within a call, the
  list order breaks ties: the last in the list is the latest.
- **Why:** core orders marks by record identity. First built as "the phone's time must be within 10 minutes of the
  server's"; the whole-branch review (I2) found any co-member can push every phone's clock state forward with one
  far-future record (it never goes back), after which honest phones failed every mark and remembered the refusal for
  good. The confirmation (F1) then found two marks in one call shared a time and were ordered by the random device.
- **Risk if wrong:** none found: the order of marks is now the server's. A transaction retry stamps afresh (harmless:
  the id is checked free). A phone that put its own two ids in the wrong order in one call would pick its own other id
  as the latest (both are the account's; the alias repair fixes the reading).
- **Overturn:** back to the phone's identity with a clock bound. Reopens I2. Not recommended.
- **Status:** merged; not deployed (Functions rounds 5 and 6).

#### 55: a daily quota of 100 held records per account; the join is free

- **What:** `sharePlaces` is charged against a new daily limit `places` (100 records), before anything is read. The
  join's own records cost nothing (invites are limited already).
- **Why:** each record reads the group's log, the place's invites and every member's log once per call; without a
  bound one account could run up reads on your budget (as D-229's 48).
- **Risk if wrong:** too low, an account in many groups waits a day for some marks; too high, billing exposure. A
  forged claim naming your id costs one unit when your phone asks for it (residue).
- **Overturn:** change the number in `people.js`. Tiny.
- **Status:** merged; not deployed.

#### 56: a held record that fails a check never fails the join (amended by 63)

- **What:** at the join, a record that fails an ownership check is skipped and logged by code; the person still joins,
  and the mark comes at the next push.
- **Why:** the mark protects the place; it must never cost someone the place itself.
- **Risk if wrong:** a skipped record leaves the place unmarked until the next push (the G-08 window).
- **Overturn:** fail the join instead. Small; makes an ownership check block joining.
- **Status:** merged; not deployed. 63 narrows it; 66 is the one refusal that fails the whole join.

#### 57: `peekInvite` answers the group and the place

- **What:** the invite preview also returns `groupId` and `memberId`.
- **Why:** the phone must build the join's held record before it calls `acceptInvite`.
- **Risk if wrong:** the link's holder learns the two ids before joining. They are what joining reveals anyway, and the
  link is the invitation.
- **Overturn:** drop the fields and mark only at the next push (the join-time mark goes). Small.
- **Status:** merged; not deployed.

#### 58: the server re-marks a place ever removed that its caller held, and a rejoin carries its old places

- **What:** a mark for a place ever removed (added back or not) is accepted from the account that held it: its own
  ended invite for that place, or its own creator place, and in both cases no other account ever used an invite for
  the place. `sharePlaces` needs the caller to be a member, so a removed account re-marks once it has rejoined. The
  rejoin's join sends its old places with the new one (`records`, 1 to 11).
- **Why:** the first review found an honest person removed from an unmarked place and invited back got a mark only for
  the new place, so their old history moved onto it and the removal's settlement counted twice for everyone.
- **Risk if wrong:** a former holder marking a place that changed hands since; 64 makes that impossible from 0.5.0 on.
  Legacy: a place that changed hands before 0.5.0 cannot be re-marked by its later holder after that holder's own
  removal.
- **Overturn:** no re-mark. Breaks every honest rejoin from an unmarked place. Not recommended.
- **Status:** merged; not deployed.

#### 59: a marked place counts from when its member was added

- **What:** when one id holds two places (an honest rejoin, both marked), its history stays on the one added first,
  whatever order the two marks were written in.
- **Why:** the marks can arrive in either order; the person's old place is always the earlier member.
- **Risk if wrong:** a member added with a backdated time (a phone-written identity) can reorder one account's own two
  places (residue M-2: that account's history moves to the new place and the removal's settlement counts twice).
  Bounded to one account; no takeover of someone else.
- **Overturn:** order by the mark's time instead. Small core change; then the result depends on mark order.
- **Status:** merged.

#### 60: the mark wins over a raw member added with its id; no raw place as "you" but the creator's own

- **What:** if a member adds a placeholder whose id is someone's held id, the mark still says whose it is (the
  placeholder is just an empty member to remove). A held id aliases only to the member holding it (the earliest-added
  holder), and a phone never places itself at a raw id held elsewhere. The server refuses a mark whose "you" is another
  raw place of the group, except the caller's own former creator place.
- **Why:** the first review found any member could void a person's mark with one `addMember`; the controller found
  neither writer refused a copied placeholder id as "you" (a takeover of that placeholder's history); the core
  re-review found a removed creator who rejoined had no place for good, and that a phone could still place itself at a
  raw id held elsewhere.
- **Risk if wrong:** an `addMember` of a victim's id written before the victim's mark denies that mark for good
  (residue M3, a denial, not a takeover).
- **Overturn:** let the raw member win (D-229's reading). Reopens the one-record denial.
- **Status:** merged; not deployed.

#### 61: one account may mark one place once per id

- **What:** the server's duplicate rule is per (group, place, id): an account on two phones may mark one place under
  each of its ids; the new place's record uses the account's "you".
- **Why:** the Task 4 review found the per-place rule made the server refuse a whole call from any two-phone account,
  dropping the real place's mark for good.
- **Risk if wrong:** one extra mark per id per place; core keeps the latest, and 62 keeps a phone from asking twice.
- **Overturn:** one mark per place, and the phones send one id only. Small, both phones.
- **Status:** merged; not deployed.

#### 62: the phones ask from their own claim records, with or without a place; the join is built one record at a time

- **What:** each phone asks the server for every claim of its own (from its own records and the group's), minus what
  is marked or already answered, and minus a member one of the account's ids already holds, even in a group where it
  currently has no place. The join builds its records one at a time, requires the new place's record, sends the
  invite's own (place, id) once and another id's claim on that place as its own record, at most 11, the new place last.
- **Why:** two honest rejoins (a removed creator, and a rejoin whose new mark failed) ended with no place for good,
  because core hides a voided claim and the job only asked where there was a place. The whole-branch review found a
  two-phone account re-marked one member under its other id, flipping the latest mark (M1). The final wave found one
  bad old place could drop the whole join's records.
- **Risk if wrong:** a few extra asks the server refuses (each asked once); forged claims naming your id are asked too
  (residue: a filter by the claim's author is the fix).
- **Overturn:** ask only from the place core reports. Small; the two rejoins lose their place again.
- **Status:** merged.

#### 63: a malformed join list fails the join; a record that fails a check is skipped

- **What:** at the join, a list of the wrong length, with a duplicate, a record of another group, none for the
  invite's place, or the old single `record` field is refused (INVALID_ARGUMENT, the invite unspent). A well formed
  record that fails a check is skipped (56). At least one record must name the invite's place; each is checked alone.
- **Why:** a malformed list is a phone bug; failing loudly with the invite unused lets a fixed build retry.
- **Risk if wrong:** a phone bug stops joins until fixed; both phones test the shape against the server's own rule.
- **Overturn:** skip malformed lists too. Small.
- **Status:** merged; not deployed.

#### 64: a place someone was ever removed from is never invited again

- **What:** creating an invite to a place ever removed (`createInvite`, `inviteUser`, `inviteKnown`) or accepting an
  older link to one is refused ("That changed on another phone. Try again."), even if a member adds that place back.
  Removed people rejoin through a new place, as the phones already do.
- **Why:** the Task 2 review found a removed place invited again let an earlier holder outrank a later one, or a mark
  stamped ahead beat a later joiner's for good; the round 2 re-review found adding the place back reopened both. It also
  closes G-10's "the joiner may claim a member who has gone".
- **Risk if wrong:** an older link to a removed place shows a message that suggests trying again, which cannot work
  (copy-pass residue).
- **Overturn:** allow re-invites (54's server stamp now orders marks by the server's time). Medium; Functions only.
- **Status:** merged; not deployed.

#### 65: a new install's first join carries its mark

- **What:** a phone with no "you" yet (a brand-new install) mints one before it calls `acceptInvite`, so the join sends
  its held record and the place is marked in the join itself.
- **Why:** the Android re-review observed the commonest first join sent the invite alone, leaving the new member
  unmarked until its first push (the G-08 window). The first records had it as a residue; the whole-branch review
  called it a decision.
- **Risk if wrong:** a first join that then fails leaves the minted "you" behind (local, harmless: a join mints one
  anyway). On a second phone of an existing account the minted id is not yet the server's, so its join mark is skipped
  until the pull (the confirmation's F2 window).
- **Overturn:** no records without a "you": the new place is marked at the next push. Small, both phones.
- **Status:** merged.

#### 66: the first account to mark an id keeps it

- **What:** both writers refuse a held record whose id another account's mark already names in that group on a member
  who is not removed: `sharePlaces` skips it, and `acceptInvite` refuses the whole join with the existing "Someone has
  already joined as this person." text, nothing written, the invite unspent. "Not removed" is the server's view, so a
  phone re-adding the copier's member blocks the owner again. The owner's remedy is removing the copier's member; the
  owner then joins and is marked only under another of its ids (46 still refuses the copied one, because the copier's
  log holds it), and on the owner's own phone the copier's removed place merges into "you": its history and "who paid"
  lines read as the owner's there, locally and for good (its balance was settled at the removal).
- **Why:** the whole-branch review (I1) found an id copied past the server's ownership checks (held only in a
  non-member's log, not yet in its owner's cloud log, or a purged account's) that reached a mark voided the owner's own
  claim: the copier took the owner's records in that group, where D-229 had a denial.
- **Risk if wrong:** honest people refused: one phone switched between two accounts (the second account's join is
  refused while the first's place is present; fails closed); and the seconds after a second phone's first join, when a
  watching co-member's copy wins (F2, a takeover of that window's records). The HELD text does not say what happened
  (copy-pass residue).
- **Overturn:** drop the check (reopens the takeover). A later hardening, if wanted: a create-only id registry, first
  writer by server time, checked by both writers and the alias repair. Medium.
- **Status:** merged; not deployed (Functions round 5).

#### 67: the "Unknown member's ... moved" line on your own phone is fixed in D-230

- **What:** the look found that on a removed (or rejoined) person's own phone, the line for their moved balance read
  "Unknown member's 550.00 moved to Al" while everyone else read "Bo's". It is older than D-229 and D-230: D-210's history
  line looks the removal record's raw place id up in the history names, which name shown members only, and core has
  merged that place into "you" on the person's own phone since D-154 and D-158 (proved by a throwaway core test on the
  branch and on its base, with and without the rejoin; `.superpowers/sdd/d230/f1-attribution.md`). Fixed in D-230's
  branch before the gate, both phones only: the history names also give every place merged into "you" the account's own
  name, and an applied alias's id its member's name, only where no name exists, and only when the phone has a "you"
  (as the ledger's merge). No core change, no new copy.
- **Why:** it shows in every removal and rejoin, the fix is a few lines per phone with twin tests, and removal records
  keep raw ids for good, so the history must name them.
- **Risk if wrong:** a raw id named wrongly in a history line; no existing name changes, and the balances are untouched
  (their data is already merged).
- **Overturn:** revert the two lines per phone: the line reads "Unknown member" again on the person's own phone. Tiny.
- **Status:** merged with D-230 (the F1 rounds, ab4adc2 and a074837: `.superpowers/sdd/d230/f1-fix-report.md` and
  `f1-fix-report-2.md`; reviewed, Approved with minors, both fixed; six twin tests per shell, ten sabotage rows, each
  exact). Re-looked on both phones: PASS (`f1-look-report.md`).

### The D-235 chain, 103 to 128: the pre-release audit's fixes

D-235 is your instruction of 2026-10-19: an Opus audit of everything, bugs and security, before the Fable audit, fixing
as needed. Five read-only auditors (core, Firebase, iOS, Android, cross-cutting) read main at c0b61d7; the triage
(`.superpowers/sdd/d235/triage.md`) split the fixes into five waves (A core, B Firebase, C iOS, D Android, E
cross-cutting), each built by an Opus implementer and reviewed by an Opus reviewer, round after round until approved;
then a whole-branch review, a paired look on throwaway devices, look fixes and a re-look. Reports, reviews and the
audits are in `.superpowers/sdd/d235/`. What the audit left for Fable (the G-items, core M2, the doubts) is listed in
BUILD-LOG's D-235 residue and in CLOUD-SECURITY section 7, untouched.

Status: **merged** on core and both phones unless an entry says otherwise; the Firebase parts are **merged, not
deployed** (they go out at 0.5.0 in 126's order). 119 to 126 are yours; 127 is reverted.

#### 103: core checks the money of every group record at ingest and of every record before it is written

- **What:** a new core check (`straumr/check.rs`, exported as `ffiCheckExpense` and `ffiCheckSettlement`) holds an
  expense to these rules: every id 32 lowercase hex, a currency core knows, an amount from 0 to 10^15 minor units, at
  least one share, nobody holding two, no negative share, shares summing to the amount exactly, and a frozen
  conversion equal to its own rate (with the shares summing to the converted amount too); a settlement has two
  different ends and an amount above 0. Membership is not checked (it depends on history). Both phones refuse a group
  record that breaks them before it reaches the ledger, and run the same check before Add, Settle, Settle all,
  removal's settle-first and import write anything; Edit goes through core's revision encoder, which runs it too.
- **Why:** core H1: nothing checked a co-member's money, and one bad record broke export for every member (guarantee
  4), settle plans and the overview.
- **Risk if wrong:** an honest record refused. Two known: a 0.4.0 Adjustment expense with a negative share (core L1
  was live then) is refused on a fresh pull, and an edit already in a vault with a negative share or a note over 1,000
  characters now reverts on that phone at the update (the final review's minor 1; internal testers only); and an
  amount-changing conversion is refused, so a SKIPTI JSON import carrying one is refused whole (no phone writes one
  today; FX entry must settle it first).
- **Overturn:** remove the ingest call, one line per phone; that reopens H1.
- **Status:** merged (core and both phones). 111 narrows the ingest half to group logs.

#### 104: a received stamp far in the future no longer moves this phone's clock, and an edit's winner is causal

- **What:** core ignores a received record stamp more than 24 hours ahead of this phone's wall clock (D-158's rule,
  lost since), and a full counter carries into the next millisecond instead of wrapping (a repeated stamp would
  replace a record in the vault). **Amended (wave A review I1):** the last writer of an expense is causal: a revision
  that another revision saw (its `seen`) never wins over it, whatever the stamps; edits that did not see each other
  order by stamp as before. The record still stores and shows its own stamp.
- **Why:** core H2, iOS H3, Android M4: one phone's clock, or one hostile member, dragged every member's clock forward
  for good. The review found that the bound alone, with a pure stamp order, let an honest edit made after a
  far-future revision silently lose; the causal winner fixes that and also stops a maximum-stamped edit freezing an
  expense.
- **Risk if wrong:** a hostile revision listing guessed future ids in `seen` could pre-empt an honest edit for a few
  seconds (it needs the exact millisecond; little gain): for Fable. Group operations (renames, membership, pictures)
  have no `seen` and stay ordered by stamp; the rules' 24-hour bound (105) is their guard.
- **Overturn:** back to the pure stamp order: a small core change, and I1 comes back.
- **Status:** merged (core; the amendment changed no FFI).

#### 105: the rules bound a record's shape and its stamp to the server's time plus 24 hours

- **What:** `firestore.rules` accepts a record only in the exact shape the phones write: `type` 1 to 3 digits; `hlc`
  exactly millis (0 to the server's time plus 24 hours), counter (0 to 2^32 - 1) and device (32 hex); `payload` exactly
  version (1), epoch, flags, a nonce of at most 64 bytes and a blob of bytes of at most 192 KiB (1.5 times the largest
  record a phone writes, a 128.1 KB picture op). Records the Functions write keep the same stamp bound and version.
- **Why:** core's bound protects a phone's clock, not the log; a hostile writer could still put a far-future stamp or a
  huge payload into it, and an unbounded payload let a member break every group Function (Firebase M1).
- **Risk if wrong:** an honest phone refused: a clock more than a day fast, or a 0.4.x phone whose stored clock was
  dragged ahead before the bound existed (0.4.0 has no parking, so its push stops at the first refusal: the final
  review's minor 7, your timing at the deploy, 126). 0.5.0 phones park such a record (114).
- **Overturn:** widen the bound in the rules (one line and a deploy).
- **Status:** merged, NOT deployed.

#### 106: the iPhone's app lock and privacy cover sit in their own window above every sheet

- **What:** the lock and the app-switcher cover are drawn in a separate window above every presentation the app makes;
  while it is up, VoiceOver cannot reach anything under it, the keyboard is put away, and an invite link or a tapped
  push opened meanwhile waits until unlock. The cover goes up the moment SKIPTI leaves the front. A sheet that was open
  stays open under the lock, as it was.
- **Why:** iOS H1: the lock was an overlay, so an open sheet stayed above it and usable, the app switcher showed it,
  and an invite opened over the lock.
- **Risk if wrong:** a twin difference after unlock: the iPhone returns to the open sheet, Android to the main screen
  (Android draws the lock in place of everything). VoiceOver under the curtain is not yet checked on a device.
- **Overturn:** the overlay again, plus closing every sheet on lock: large.
- **Status:** merged (iOS). The look passed it: the switcher showed the cover, taps were blocked, the sheet was intact
  after unlock, an invite waited.

#### 107: a reinstall never signs you back in without asking, and only the vault's own account syncs it

- **What:** on its first launch a new iPhone install with no vault signs out any Firebase session an earlier install
  left in the keychain (an install marker tells a new install from an update). On both phones sync, the phone's
  registration, push and the profile share run only when the vault's recorded owner is the signed-in account; a vault
  that cannot be read means no owner: nothing syncs that launch and nobody is signed out.
- **Why:** iOS H2 and the cross-cutting audit's H1: a reinstalled iPhone was signed in without a sign-in and synced
  (guarantee 5: an account is never implied), and a later different account could inherit the vault (D-168's
  one-account-per-phone guard).
- **Risk if wrong:** a 0.4.0 tester whose iPhone was silently signed back in by this bug is signed out once on the
  first 0.5.0 launch (a release-note line).
- **Overturn:** remove the marker and the gate: the hole comes back.
- **Status:** merged (both phones; Android's owner gate in round 4 and the final wave). The look: a reinstall opened
  signed out and wrote nothing to the emulators.

#### 108: test surfaces and plain local networking exist only in debug builds, on both phones

- **What:** the iPhone's proof launch arguments and screens compile only into Debug, as Android's run only in
  debuggable builds; the iPhone's permission for plain HTTP to local hosts is in the Debug Info.plist only, and
  Android's loopback cleartext moved to the debug resources. The iPhone's dead FX code with its embedded test key is
  gone.
- **Why:** twin parity and defence in depth (iOS L1, cross L3 and L4).
- **Risk if wrong:** a check that needs a proof on a Release build cannot run one (none does).
- **Overturn:** move the `#if DEBUG` lines back.
- **Status:** merged (the Release Info.plist has no transport exception; `strings` finds no proof argument).

#### 109: a launch without an account is measured on both phones, and any Google contact it makes is removed

- **What:** a network capture of a no-account launch on throwaway devices, per phone. Android: Play services fetched
  its emoji font from fonts.gstatic.com for SKIPTI at every launch, so the emoji initializer is removed from the app's
  manifest (Android draws the phone's own emoji) and the Play manifest check refuses it coming back. The iPhone looked
  up www.google.com (DNS only): 116.
- **Why:** the cross-cutting audit's M2: guarantees 5 and 6 had never been measured.
- **Risk if wrong:** on an older Android phone a very new emoji may draw as an empty box.
- **Overturn:** put the initializer back (one manifest line) and accept the fetch: a guarantee change, yours.
- **Status:** merged. Measured after: no Play services font fetch on Android; no lookup on the iPhone (116).

#### 110: free text at most 1,000 characters, and this phone's own stamps at most 23 hours ahead

- **What:** (a) core refuses a revision's note, category or editor, a group's name or an import's note or category over
  1,000 characters (member names keep their 64). (b) This phone stamps at most 23 hours past its wall clock; when its
  stored clock is further ahead it lowers it and starts a new "era" in the counter's top 8 bits, so a lowered stamp
  never repeats an id it wrote before; a received stamp moves the clock at most to 23 hours ahead and never back, and
  never takes another phone's lower era.
- **Why:** the wave B review's I2: once the rules refuse past 24 hours (105), an honest phone with a fast or dragged
  clock, or a very long note, would be refused for good; a plain clamp would reuse ids, which the cloud reads as
  already uploaded, losing the record.
- **Risk if wrong:** the look's F2: a phone whose clock runs fast stamps up to 23 hours ahead, and its co-members' edit
  times follow it (127 tried to stop that and 128 reverted it); after 255 lowerings the last era is kept.
- **Overturn:** drop the clamp; the rules then refuse such a phone's records and 114 parks them.
- **Status:** merged (core; no FFI change).

#### 111: the ingest money check runs on group logs only

- **What:** both phones check the money of records from a group's log, not from the account's own personal log.
- **Why:** the personal log is written only by this account's own phones; checking it would refuse old 0.4.0 personal
  records (a negative Adjustment share) with no security gain. Android chose it; the iPhone matched.
- **Risk if wrong:** a bad personal record from an old build still reaches this phone's export (export is unchanged).
- **Overturn:** add the check to the personal arm, one line per phone.
- **Status:** merged (both phones).

#### 112: the note is capped by the phones at core's 1,000 characters, with no new core export

- **What:** both phones stop the Add and Edit note field at 1,000 characters counted as core counts them (Unicode
  scalars on the iPhone, code points on Android), cut at the last whole character so an emoji is never split, and
  refuse a longer note before any write with core's own words; a parity test on each phone reads core's
  `TEXT_MAX_CHARS`.
- **Why:** a new expense's note never passes through core on the way out (the phones build that record), and core's
  check takes no note; adding an export would have changed the bindings while two waves built on them.
- **Risk if wrong:** a new write path that forgets the check writes a note the rules or other phones refuse.
- **Overturn:** add an additive `ffi_check_text` later and call it from the phones.
- **Status:** merged (both phones).

#### 113: the server counts used invites' makers and removal writers as authors of a group's log, with no backfill

- **What:** the Functions read a group's log author by author (members, former members, the creator, accounts the
  purge took out, recorded as `departed`) and since round 3 also the makers of the group's used invites and the writers
  of its removals; that needs a second composite index, invites (groupId, usedBy). No production data is rewritten.
- **Why:** an account purged before `departed` existed has no entry, so a place it added read as never made and the
  person who joined that place could not be removed (the wave B confirmation).
- **Risk if wrong:** an account purged before the deploy that neither made a used invite nor removed anyone still has
  its ops unread (narrow; round 4 also lets removeMember remove a place held through an accepted invite whatever the
  log shows). Fable's read-only checks look for such accounts.
- **Overturn:** a one-time backfill of `departed` in production: a production write, your yes.
- **Status:** merged, NOT deployed.

#### 114: a record the cloud refuses is parked, retried once a day up to three times, and never stops the rest

- **What:** a record the rules refuse while the server says it does not hold it is set aside on this phone; the push
  goes on with everything else. It is tried again 24 hours after its last refusal, or at once if the phone's clock went
  back, up to three more times; then it stays on the phone. One that goes up is let go. Since the look's F4, a group
  record whose write and existence read are both refused, while the phone is still signed in as the account that
  pushed it (the person was removed from the group), is parked the same way instead of stopping every later push.
- **Why:** the wave B review's I2 (one refused record stopped the whole sync, silently, on both phones) and the
  shells review's M5 (parked for ever never heals, and the commonest honest cause, a fast clock, heals once fixed).
- **Risk if wrong:** a phone clock more than about three days fast, or a member let back in more than about three days
  after a removal (Confirmation 2), keeps those records on the phone for good; 122's line says so on the group.
- **Overturn:** the numbers are two constants per phone.
- **Status:** merged (both phones).

#### 115: Android's dependencies are checksum-verified, after two documented metadata refreshes

- **What:** `android/gradle/verification-metadata.xml`: 428 components, 710 sha256 checksums, no trusted exceptions;
  a tampered checksum fails the build. Getting there needed the POM and module files Gradle's cache clean-up had
  dropped: CLAUDE.md 7's documented `--refresh-dependencies` for the 11 configurations the gate resolves, then one
  more for `:app:processPlayDebugResources` (aapt2's POM). No version changed (the dependency and build environment
  listings are byte-identical before and after).
- **Why:** the cross-cutting audit's M3: the graph was neither locked nor verified, and with the Developer Tools
  exemption on, nothing else checks what Gradle runs.
- **Risk if wrong:** trust on first use (the checksums pin what the registries served that day); the refreshes fetched
  XML metadata from google() and Maven Central, not executables, under the controller's reading of CLAUDE.md 13; you
  may read rule 5 more strictly. A new Android dependency must add its checksums; a cache clean-up that drops POMs
  fails offline builds until refreshed.
- **Overturn:** delete the file.
- **Status:** merged.

#### 116: the iPhone starts Firebase only once there is an account or a sign-in tap

- **What:** `FirebaseApp.configure` no longer runs at every launch: one gate configures Firebase when this install has
  an account (117) or when a sign-in button is tapped, then applies the reinstall sign-out (107) before the account
  attaches. Messaging's auto-start stays off.
- **Why:** 109's measurement: every no-account iPhone launch looked up www.google.com (Messaging's reachability
  check), a Google hostname without an account (guarantee 6).
- **Risk if wrong:** a path that needs Firebase before the gate (the database configures through the gate as a safety
  net; nothing reaches it without an account, measured).
- **Overturn:** configure at every launch again and record the DNS lookup in GUARANTEES and THREAT_MODEL.
- **Status:** merged (iOS). Measured on a throwaway simulator: no lookup, no resolver call and no connection without an
  account; signed in, it configures and syncs.

#### 117: the iPhone configures at launch only with an owner and no sign-out since, or to settle an owed token delete

- **What:** a mark in the vault: "1" when a sign-in succeeds, "0" at every sign-out and whenever a session ends. A
  launch configures Firebase only when the vault has an owner and the mark is not "0". No mark (an install from before
  this build) configures at every launch until a sign-out writes "0" (Corrected, D-235: the round 5 report said the
  session found then writes the mark; nothing writes "1" but a sign-in, the final review's minor 6). **I-1, the final
  review:** after an offline sign-out the owed delete of the push token at FCM never settled, so pushes kept reaching a
  signed-out phone; now any sign-out that owes that delete unregisters the phone from Apple's push service at once
  (local, no network), and a later launch with the delete owed configures Firebase without attaching the account,
  settles it, and stays quiet.
- **Why:** with an owner alone, a signed-out install would start Firebase at every launch; I-1 was round 5 undoing
  round 4's push fix.
- **Risk if wrong:** signing in within the 5 seconds a settle-mode delete may still run could delete the new token (no
  pushes until the next token refresh; Android has the same window): residue.
- **Overturn:** configure whenever there is an owner.
- **Status:** merged (iOS; Confirmation: I-1 closed).

#### 118: the iPhone's safe picture decoder uses core's size limits

- **What:** pictures from the cloud decode only within core's caps: a photo 2048 pixels a side and 2048 x 1024 pixels
  in all, a preview 512 and 512 x 512 (it was 4096 and 16 MP), as Android's decoder does.
- **Why:** the final review's minor 5: twin parity.
- **Risk if wrong:** display only; previews default to the photo caps, looser than Android's preview caps, and core's
  ingest already refuses a preview past 512.
- **Overturn:** two constants.
- **Status:** merged (iOS).

#### 119 (yours, by pop-up): a CSV export keeps every cell exactly as typed

- **What:** no formula escaping: the JSON and CSV exports hold exactly what was typed.
- **Why:** your call: the export is the person's data, verbatim, in an open format (guarantee 4).
- **Risk if wrong:** opening a co-member's CSV in a spreadsheet may run a formula someone put in a note (core M1).
- **Overturn:** prefix a cell starting with =, +, - or @ with an apostrophe (core M1's proposed fix): a format change.
- **Status:** your call, 2026-10-19; no code; the risk is in BUILD-LOG's D-235 residue.

#### 120 (yours, by pop-up): group pushes keep showing the note and the amount on the lock screen

- **What:** D-196's push text stays ("Al added Dinner · $40.00", titled with the group); SPEC section 3.8, which said
  pushes carry only "New activity in <group>", is amended to match.
- **Why:** your call: the useful push is the one that says what happened.
- **Risk if wrong:** anyone holding the phone reads amounts on its lock screen; the phone's own preview setting decides
  (CLOUD-SECURITY G-19). The text passes through Apple's and Google's push services in the clear.
- **Overturn:** "New activity in <group>" only: a Function and phone change.
- **Status:** your call, 2026-10-19; the SPEC amendment rides with this record.

#### 121 (yours, by pop-up): an invite names the inviter and the group from the server's records

- **What:** built in D-235: `inviteUser`, `inviteKnown` and (the controller's extension) `createInvite` build the push,
  the in-app invitation and the link from the server alone: the inviter's profile name (none: "Someone invited you to
  X" in the push, "You are invited to X" in the apps), the group's latest name in its log (else its document's, else
  "Group"). The names the phone sends are still checked and never used.
- **Why:** Firebase M2: a caller chose the text a stranger saw under SKIPTI's name.
- **Risk if wrong:** the profile name is still the account's own free text (an account may call itself "SKIPTI
  Support"), and the "You join as X" name stays the group's own text.
- **Overturn:** back to the phone's names.
- **Status:** merged, NOT deployed; the three Functions now read the log, so they too need the indexes READY first.

#### 122 (yours, by pop-up): a quiet line when a change could not sync

- **What:** "One change could not sync. It is still on this phone." on the group's page, under its header, in the
  header's own small dim type, while any of this phone's records in that group is parked (114); no alert, count,
  button or icon; it goes when the record goes up.
- **Why:** your call: say it, quietly, where it belongs.
- **Risk if wrong:** a person who never opens the group's page does not see it.
- **Overturn:** the words or the place, one line per phone.
- **Status:** merged (both phones, twins: the Android test reads the iPhone's words).

#### 123 (yours, by pop-up): zeroing the vault key's copies waits for 0.5.1

- **What:** core L4 (the key copied around in memory without being zeroed) needs the `zeroize` crate, a new dependency:
  in 0.5.1, with CONTRIBUTING rule 4's write-up.
- **Why:** your call: a new dependency is not added in an audit's fix wave.
- **Risk if wrong:** until then a key copy may stay in freed memory (a compromised phone is out of scope, THREAT_MODEL).
- **Overturn:** add it now, with the write-up and your yes.
- **Status:** your call; residue.

#### 124 (yours, by pop-up): docs/CLOUD-SECURITY.md is never public

- **What:** the step before the repo goes public removes it from the public repo, its history included, or publishes
  the repo without it.
- **Why:** your call: it maps every control and every open gap of the live cloud.
- **Risk if wrong:** none in security; a public reader cannot see how the cloud is run.
- **Overturn:** one line.
- **Status:** your call; added to CLAUDE.md's pre-public step with this record.

#### 125 (yours, by pop-up): decisions 83 and 84's words are kept

- **What:** Haptics reads "A tap when it is done"; You's account row reads "Google · 2 phones".
- **Why:** your call: both fit a 360 dp phone, which the old words did not (D-234).
- **Risk if wrong:** none; copy only.
- **Overturn:** one line per phone, and the width tables follow.
- **Status:** your call; closes the first half of open item 11.

#### 126 (yours, by pop-up): the server goes first at the 0.5.0 deploy

- **What:** the two composite indexes (records type, authorUid, createdAt; invites groupId, usedBy), then wait until
  both are READY; then the Functions; then the rules; then the apps; testers are asked to update the same day.
- **Why:** your call. Until the indexes are READY every log-reading Function fails in every group; the rules refuse
  what a 0.4.x phone with a dragged clock writes, and 0.4.0 cannot park it.
- **Risk if wrong:** a tester who does not update stays stalled until they do (the final review's minor 7).
- **Overturn:** another order; the indexes must still be READY before any Function goes out.
- **Status:** your call; the 0.5.0 deploy's order.

#### 127 (reverted by 128): a received stamp moves this phone's clock at most 10 minutes ahead

- **What:** the receive clamp at the wall clock plus 10 minutes, not 23 hours (7ef0bf5), against the look's F2.
- **Why:** with the causal winner (104) no phone needs to stamp past what it saw to supersede it.
- **Risk if wrong:** found at once: group operations have no `seen`, so a re-add or a rename made after a fast phone's
  operation would lose to it for up to a day.
- **Overturn:** see 128.
- **Status:** reverted by 128 (763cf36); the phones' libraries were never built with it.

#### 128: decision 127 is reverted; F2 stays residue

- **What:** core back to exactly what it was before 7ef0bf5.
- **Why:** group-operation correctness (a re-add or a rename silently losing for up to a day) outweighs F2 (edit times
  up to 23 hours ahead when a co-member's clock runs fast: rare and cosmetic; before D-235 it was unbounded). A causal
  fix for group operations needs a new export and both phones: a later unit.
- **Risk if wrong:** F2 shows to real people more often than thought.
- **Overturn:** a `seen` on group operations, then 127 again.
- **Status:** merged (763cf36; Confirmation 2: core byte-identical).

#### 129 (yours, by pop-up): the vault key's copies are zeroed in 0.5.0

- **What:** core zeroes every copy of the vault key it holds (keystore loads, the stored test keystore, the fresh draw,
  the PRAGMA text, the FFI bridge's bytes), with no dependency added (`zeroize` was already one).
- **Why:** decision 123 put this in 0.5.1 on the audit's word that it needed a new dependency; it did not.
- **Risk if wrong:** none to data (the PRAGMA text is byte-identical; an old-keyed vault opens: tested).
- **Overturn:** revert 2ec241c and 9bb180c.
- **Status:** merged with D-236 (supersedes 123).

#### 130 (yours, by pop-up): the privacy page names the profile picture

- **What:** "Your profile: the name you show others, a username if you choose one, and a picture if you add one, shown
  small to your groups."
- **Why:** since D-211 a preview of the picture is shared with groups; the page did not say so.
- **Risk if wrong:** none; it is live only after the Hosting deploy with your yes.
- **Overturn:** other words in chat.
- **Status:** committed in D-236; not deployed.

### The D-237 chain, 131 to 134: the Fable audit's fixes

D-237 is your Phase A (docs/RELEASE-0.5.0.md): the pre-release security audit, done in a Fable session on main at
d42a901, with the three fixes it found worth making before the deploy. The audit's report is
`.drafts/audit-050/findings.md`; what it left recorded (not fixed) is in CLOUD-SECURITY section 7 and BUILD-LOG's
D-237 residue. Status: **merged** on core and both phones; the Functions part is **merged, NOT deployed** (it goes
out with the rest at 0.5.0 in 126's order; no new index, no rules change).

#### 131: a place this account claimed reads as "you" in its own group only

- **What:** core merges the members an account claimed into "you" per group (`claimed_in`), the member list and
  the place rules per group, and the FFI merges through aliases (group, place, you) that the projection now emits;
  `Yous::with_aliases` takes an id aliased to you out of the global set. `other_you_ids` keeps listing every claimed
  place (the shells name them and ask "is this me" with it). Both phones' `Placing` (what a phone writes as its
  place in a group) leaves out the places claimed only in other groups (`mineIn`).
- **Why:** the D-235 core audit's doubt 1, confirmed: a co-member who shares a group with you and Carol can make a
  group whose member id is Carol's place id (a modified phone mints any id) and invite you to it; your phone's
  claim there put that id into your global "you" set, and from then on every record of Carol's in the shared group
  read as yours on your phone (balances, the settle plan, who paid), for good. With a two-phone account, a record
  you wrote there could carry her share on your place, into the log every member reads.
- **Risk if wrong:** a claimed place that was legitimately meant to read as you in ANOTHER group. There is none:
  a place id is minted per group; the account's own "you" records stay global as before.
- **Overturn:** a global `mine` again (one closure in `project`): the takeover of a co-member's history comes back.
- **Status:** merged (core and both phones; twin tests on both, four core tests updated to the per-group aliases).
  Round 2 (the review's I1): the list of OTHER people's aliases is also filtered per group, so a co-member who copies
  someone's "you" id (not their place) into a group it makes cannot orphan that person's records in a shared group
  on your phone (they read as their place, as before); tested in the same core test.

#### 132: each own record stamp adds a random step to its counter

- **What:** `Hlc::tick_by` adds a 20-bit random step to the counter of every stamp this phone writes
  (`ffi_hlc_next` draws it; `tick` is the old rule, step 0); the low 24 bits count within a millisecond and carry
  into the next, never into the era bits. The wire format is unchanged (a u32 counter).
- **Why:** once a phone received a record stamped at T (up to 23 hours ahead, which the rules allow), its next ids
  were (T, c+1), (T, c+2), ... with its device id in every record: a co-member could list the next thousand in a
  revision's `seen` and so pre-empt every edit that phone made to that expense for a day, silently and again the
  next day (D-235 recorded the attack as needing an exact millisecond; the receive rule supplies it). A next id is
  now one guess in about a million, and a revision's blob holds about 3,900 guesses.
- **Risk if wrong:** a heavy import stamps 16 records a millisecond and runs a few hundred milliseconds ahead of
  the wall clock (well inside the 23-hour bound); a phone that writes 2^24 records in one millisecond does not exist.
  Round 2 (the review's M1): a clock pinned at the 23-hour bound by a co-member's stamps carries past it every few
  stamps, and lowering it at once would spend one of the 255 eras each time (an import while pinned could spend them
  all, and then a stamp could repeat and replace a record); the carries may now run 5 minutes past the bound before a
  lowering (`CARRY_SLACK_MS`: about 4.8 million stamps an era), still 55 minutes inside the cloud's 24-hour rule;
  receive never moves the clock into the slack. Pinned, 100,000 stamps spend no era (tested).
- **Overturn:** `ffi_hlc_next` passes 0.
- **Status:** merged (core; no FFI change).

#### 133: a participant id belongs to the account that registered it first

- **What:** the Functions decide "another account's participant id" (decision 47's query in `checkHeldId` and
  `shareAliases`, and `inviteKnown`'s lookup) by the earliest profile holding the id (`users/{uid}.createdAt`, the
  server's time at the profile's creation, written by the rules and never changed): a profile made later with the
  same id denies the first account nothing, and its own marks and aliases with that id are refused; an id a caller
  only copied into its log stays another's as soon as anyone registered it. Up to 20 holders are read; no index.
- **Why:** the rules take any 32 hex as a profile's participant id, so one sockpuppet profile that copies your id
  (known from any shared group) made the server skip every held-place mark of yours (`sharePlaces`, the join's
  records) and every alias repair, and made you un-invitable through `inviteKnown` (G-29). The join still went
  through, unmarked, which put your places back under the phone-claim rules D-230 was built to replace.
- **Risk if wrong:** two honest accounts on one phone with the same "you" (G-31 (a)): the first keeps the id, the
  second's marks with it are refused (before, both were). Two profiles created in the same millisecond tie and
  neither denies the other. Round 2 (the review's I2): the 20 holders are read in no order, so a read that comes
  back full fails closed for everyone (the id is another's, as the old rule read it): twenty sockpuppets deny a
  person their marks, as two did before, and never let a copy past an unread registrant.
- **Overturn:** the old `limit(2)` query in three places; G-29 and the squat come back.
- **Status:** merged, NOT deployed (Functions; three emulator tests).

#### 134: what the audit leaves for 0.5.1, and what it accepts for 0.5.0

- **What:** the backdated Create that changes a group's settlement currency and creation time (core M2: Low, G-15's
  family; the money is not converted); the `seen` and held-place ordering that the server's `createdAt` in the vault
  would make exact; the shells' cosmetic "is this me" sets that are still global (`selfPlace`, the removal rows,
  the profile share's place; the server decides every real role and the share); Firebase doubts D1 (a deleted
  group's id can be created again), D2, D3 (adoptRemovals' sole-member power equals an ordinary expense) and D5
  (a changed handle is free at once); the Auth record's display name and picture URL (a disclosure line); G-22.
  All recorded in CLOUD-SECURITY section 7 and BUILD-LOG's D-237 residue; none rates above Low for internal testing.
- **Why:** each needs a modified client and gains little, or is a product reading that is yours.
- **Risk if wrong:** a tester with a modified phone changes a group's settlement currency; the fix is the group's
  deletion.
- **Overturn:** name the one to pull into 0.5.0.
- **Status:** recorded.


#### 136: the release lint tool's checksums are added to Android's verification file (D-238)

- **What:** `android/gradle/verification-metadata.xml` gains the 12 components (24 files) of Android's lint tool at
  AGP 32.3.2 (lint, lint-api, lint-checks, lint-gradle, intellij-core, kotlin-compiler, uast, play-sdk-proto, groovy
  3.0.22, httpclient 4.5.6, kotlin-reflect and kotlin-stdlib 2.2.10), written offline from Gradle's cache with the
  gate's tasks plus `:app:lintVitalAnalyzePlayRelease`; nothing removed, no existing checksum changed, no trust entry.
- **Why:** your `bundlePlayRelease` stopped at `lintVitalAnalyzePlayRelease`: decision 115 generated the file with the
  gate's tasks only, and the gate builds no release, so the release-only lint tool had no checksums ("missing", never
  "mismatch": nothing was altered). Not a new or upgraded dependency: AGP's own pinned lint, part of the locked graph.
- **Evidence:** every file's sha256 equals the cached file, and every cached file's SHA-1 equals the one Google's Maven
  repository or Maven Central publishes for it (24 of 24); the release lint step passes with verification on; a
  wrong checksum on `lint-checks-32.3.2.jar` fails the build with "Dependency verification failed".
- **Risk if wrong:** trust on first use, as decision 115 (the published SHA-1s corroborate it).
- **Overturn:** remove the 12 components; release builds fail again.
- **Status:** merged; CLAUDE.md 7's regeneration command now includes the release lint step.

#### 135 (yours, in chat, 2026-10-19): 0.4.0 phones are not set up for the deploy; the two testers update on the day

- **What:** the runbook's Phase C set-ups on 0.4.0 phones (the lost edit for G2, an untouched Appearance for G3) are
  dropped; 0.5.0 must work flawlessly, and the two people on 0.4.0 are told to update. Their usernames are server-side
  documents (`usernames/{handle}`, `users/{uid}.username`) that neither the rules change nor the apps touch: they
  survive the update as they are.
- **Why:** your call: two testers, both reachable.
- **Risk if wrong:** G2's proof that the lost edits arrive after the update needs a 0.4.0 phone to make one first; on
  0.5.0 phones the edit path is proven by the gate and the D-235 look only.
- **Overturn:** keep one phone on 0.4.0 and run C1 before updating it.
- **Status:** recorded; the runbook is version 3.

#### 137 (yours, by pop-up, 2026-10-20): power saving stills the art only; every moment still plays (D-239)

- **What:** under Low Power Mode (iOS, including what iOS 26 and later's Adaptive Power reports) or battery saver
  (Android) only the background art pauses (curtains, stars, runes, signs). A done check, the settle celebration and
  its burst, a switch's travel and a row opening play unless Reduce Motion or Less motion is on. One rule per shell:
  `MotionClock.momentTicks` (iOS, with an art canvas `moment` flag for the burst) and `MotionState.moment()` (Android);
  a source guard on each shell allows the power state to be read by the art alone. ADR-0041's still mode amended.
- **Why:** 0.5.0 on your iPhone 17 (iOS 27) showed no animation at all with Reduce Motion, Low Power and Less motion
  off; the App icon row snapped, so the app's motion check read "reduce"; turning Low Power Mode on and off fixed it.
  iOS was reporting Low Power at launch (Adaptive Power on, very likely the source; not provable from outside), and
  D-214's rule then stilled the art AND every moment. Your LG's early stuck spell cleared on a resume (when power state
  and the animator setting are re-read): possibly the same family, a guess, unproven. The motion engine had never run on a real phone before 0.5.0.
- **Risk if wrong:** a moment draws a few frames under power saving (each lasts about a second): negligible battery.
  What runs on after a moment lands is art and still pauses: the done screens' Norse ring stops turning under power
  saving (the review's I1: it had kept turning for as long as the screen showed), and Android's done check no longer
  redraws after it lands in any theme (it drew from an endless art loop; now from the moment's own clock, as iOS).
  The art still pauses whenever the phone reports power saving, so on an iPhone 17 with Adaptive Power it may pause
  often; making the art itself livelier is U24 in the 0.5.1 plan.
- **Overturn:** the moment sites read the art's rule again (one line each).
- **Moments must be "a moment" by name:** a source guard on each shell counts every read of power saving (iOS any
  `.lowPower` and `isLowPowerModeEnabled`; Android `lowPower`, `isPowerSaveMode` and the broadcast) and allows them in
  the motion file alone.
- **Status:** merged (D-239); ships in 0.5.1 (your pop-up, session 10: 0.5.0 is out, no rebuild).

#### 138: 0.5.1's version numbers are written at its release prep, not now

- **What:** your pop-up (session 10) set 0.5.1 to read the same on both phones: iOS Version 0.5.1, Build 8; Android
  versionName 0.5.1, versionCode 5001 (5000 is on Play). The runbook (`docs/RELEASE-0.5.0.md`, version 5) records them
  and gives the changed Phase F commands; the edits to `project.pbxproj` and `build.gradle.kts` wait for 0.5.1's
  release prep.
- **Why:** every unit until then builds and is looked at as 0.5.0 (7) / 5000, so no debug build or screenshot claims a
  version that is not released, and a release-prep commit carries the bump alone, as D-236 did.
- **Risk if wrong:** none for users; a look's About screen reads 0.5.0 until release prep.
- **Overturn:** bump now (four lines, `.drafts/release-050/apply.py`'s pattern).
- **Status:** recorded.

#### 139: what a moment leaves running after it lands is art (D-239's review, I1)

- **What:** the Opus review found the done screens' Norse rune ring turning for as long as the screen showed, now also
  under power saving, and Android's done check redrawing at 30 fps forever in every theme (its canvas was an endless art
  loop). Fixed on both shells: the check itself plays as a moment; after it lands, Norse's ring turns only while the art
  ticks (iOS `MotionClock.artTicks` into `DoneCheck.moving(artTick:)`; Android `DoneCheck.runFor`), and a ring that stops
  holds the landed pose (`CheckMotion.landedElapsed`, 1.2 s) instead of jumping upright. Android's check now draws from
  its moment's own clock, which ends, as iOS's paused timeline does.
- **Why:** decision 137 splits moments (short, then done) from art (decoration that runs on); the endless turn is art.
- **Risk if wrong:** under power saving a Norse done screen's ring stands still after the check lands.
- **Overturn:** `runFor` / `moving` ignore the art rule again (one line each).
- **Status:** merged with D-239.

#### 140: one named cargo-deny exception for an unmaintained build-time crate (RUSTSEC-2026-0374)

- **What:** D-239's first full gate failed one leg, `cargo deny check`, on an advisory published that day: `basic-toml`
  0.1.10 is unmaintained. `deny.toml` gains an `[advisories]` section with exactly that id and its reason; every other
  advisory, and any vulnerability advisory (a new id), still fails the gate.
- **Why:** it is a notice that the crate is archived, not a vulnerability (`cargo audit` passes it as a warning). It
  reaches skipti-core only through `askama_macros`, a proc-macro of `uniffi_bindgen` 0.32: it runs on the Mac while
  bindings are generated and is never linked into the iPhone or Android library (`cargo tree -i basic-toml`). No
  release replaces it; the only way out is a uniffi upgrade, which is a dependency change that needs your yes.
- **Evidence:** with the exception `cargo deny check` reads "advisories ok"; without it "advisories FAILED"; the failing
  run is kept under `scripts/gate-artifacts/20261020T155210Z-*`.
- **Risk if wrong:** a compile-time tool with no maintainer; it handles only uniffi's own config at build time.
- **Overturn:** delete the entry (the gate then fails until uniffi is upgraded).
- **Status:** merged with D-239; looked at again with the next uniffi upgrade.

#### 141: a deleted group's settings and page close by sending the tab back to its root (D-240, U27)

- **What:** after Delete group (on this phone only, or for everyone) both shells post "Group deleted" and close the
  settings AND the group's page at once. On iOS the settings screen posts `.skiptiShowTabRoot` with its tab
  (`\.shellTab`) and `HomeShell` bumps that tab's stack id, as a re-tap of the bar item does; outside the shell (the
  sheet a tapped push opens) it calls the page's own close. Android's `onGone` already cleared the route and the open
  group; it now runs for the delete for everyone too, instead of going back to the group's page.
- **Why:** the look found the old iOS path broken: the group page's own `dismiss()`, called from the settings above it,
  left an empty "Group settings" screen without its tab bar, because the page's ledger card had already left the list
  that pushed it (the local delete has done this since D-210; nothing looked at it).
- **Risk if wrong:** a delete for everyone no longer shows the "You deleted" banner on the group's page right away; the
  toast says it, and the read-only copy is still there under Groups.
- **Overturn:** the delete for everyone goes back to `dismiss()` (one line per shell).
- **Status:** merged with D-240.

#### 142: the "Suggested" currency's details (D-241, U26)

- **What:** your shape (nothing ticked; the phone's currency first under "Suggested") built into the existing list:
  tapping a currency chooses it and goes back, as the list always did, so there is no separate Continue; "chosen" means
  a stored code core knows (D-221's set-up step uses the same test); the Your data row keeps showing the code new
  groups will use; the suggested code stays in Common too (Common is a fixed list).
- **Why:** the smallest change that removes the pre-tick, with one rule shared by the list and the set-up progress.
- **Risk if wrong:** the row's code before a choice may still read as chosen to some.
- **Overturn:** the row shows nothing (or "Suggested") until a choice; new copy, your words.
- **Status:** merged with D-241.

#### 143: Norse's faint text is your approved #9A8F76, and the rune floor keeps measuring it (D-242)

- **What:** palette.py derives textFaint from textDim and gave #90866E, a step darker than the mock-up you approved
  (#9A8F76); with the darker value the rune floor on the Norse page would have dimmed the runes, so the implementer had
  narrowed that floor to skip faint text. Decided: your #9A8F76 is an approved input in palette.py (as textDim, accent
  and the money colours already are) and the floor keeps measuring faint text (cap 1.00; runes unchanged at 0.79).
- **Why:** it is the value you approved, and no measured check gets weaker. Every faint pair passes (tightest on the
  raised surface, about 4.55; card top 5.59).
- **Risk if wrong:** none known; faint words are a touch lighter than the derived value.
- **Overturn:** take the derived #90866E (the floor would then need the narrowing back).
- **Status:** merged with D-242.

### The D-243 chain, 144 to 150: the livelier Norse art (U24's motion half)

#### 144 (yours, by pop-up, 2026-10-20): each Norse page rune has its own lane

- **What:** the 16 rising page runes move to 16 evenly spread lanes (2% to 94% of the width, lane k at 2 + 92k/15 %),
  assigned so neighbours alternate gold and silver where ten golds and six silvers allow, then by the least move from
  the old lanes; glyphs, sizes, speeds, leads and the flare unchanged.
- **Why:** where two runes rose over each other the faint text above them fell below 4.5:1 (today 4.23 worst, about
  5,500 frames an hour; with the flare 3.04, about 15,000). With one lane each, the hour-long scan finds no overlap and
  no failed floor on either page size (worst textDim 4.534, a lone flaring rune).
- **Risk if wrong:** the runes look more ordered than the Look Book's clusters (your call, accepted).
- **Overturn:** the old lane formula (the floor then fails on overlap frames again).
- **Status:** merged with D-243.

#### 145: the hero's runes keep left of the ᚠ, inside the inner frame

- **What:** the 7 hero runes rise in evenly spaced lanes from 5% to 56% of the hero's width, clipped inside the inner
  frame; none passes under the ᚠ.
- **Why:** a rune passing over the glowing ᚠ fails onHero's 7:1 (about 5.8 measured by hand); the mock-up had one lane
  under it and two pairs of nearly coincident lanes. The stacking scan (1d) passes on 328 and 343 wide heroes.
- **Risk if wrong:** the hero's right third holds only the ᚠ.
- **Overturn:** a hero-floor exception (not recommended: it is a floor).
- **Status:** merged with D-243.

#### 146: glows are cached discs, never blurs

- **What:** every rune halo, the ᚠ halo and the lit-ring halos are radial-gradient discs drawn from cached symbols (iOS)
  or cached unit brushes (Android); the card-edge glow is 12 concentric 0.5 pt strokes of the grown notched outline.
- **Why:** identical on both phones and exactly measurable; no second blur (decision 79's frame budget) and no per-frame
  allocation. A blurred shadow would need its own per-phone gain, as Aurora's glass did.
- **Risk if wrong:** the glow's falloff is stepped at 0.5 pt (invisible at 3x).
- **Overturn:** a blur per card (costs frames on the LG class of phone).
- **Status:** merged with D-243.

#### 147: the card-edge glow reaches 6 pt, and page text keeps 6 pt from a Norse card

- **What:** the glow's sigma is 3.0 (the mock-up's 3.5 would reach 7 pt); floored page-ground text keeps at least
  `ArtGeometry.glowReach` from a Norse card edge, pinned by a source guard on both shells at the tightest sites, and a
  line in CLAUDE.md section 10.
- **Why:** every glow band under text over a flaring rune fails textDim; the audit found iOS's Add a person labels 6 pt
  above their fields, so the reach was fitted to that gap (the glow stays clearly visible: first band alpha 0.084).
- **Risk if wrong:** a slightly thinner glow than the mock-up.
- **Overturn:** sigma 3.5 and the People labels moved 1 pt further (one spacing).
- **Status:** merged with D-243.

#### 148: the ring turns as art, from upright each time it starts

- **What:** You's Futhark ring turns once per 90 s on the art's rule (power saving pauses it; Reduce Motion and Less
  motion hold it upright); it starts from upright each time it starts ticking; the done check's ring keeps its own
  14 s clock (D-239); lit runes carry a static halo.
- **Why:** an absolute clock would jump on every visit to You; upright is the designed still frame (D-221's layout).
- **Risk if wrong:** none known.
- **Overturn:** an absolute clock (the ring would appear mid-turn).
- **Status:** merged with D-243.

#### 149: two invisible iOS pixel differences are accepted

- **What:** the iOS ᚠ's still frame differs from 0.5.0's by up to 6/255 on its antialiased rim (the symbol is
  composited at layer opacity where the Text used colour opacity; pixel-snapping the origin changed nothing), and four
  unlit ring runes sit one device pixel off when upright (the rotation wrapper).
- **Why:** invisible and inside every floor; matching exactly needs a second ᚠ symbol and its own guard for nothing a
  person can see.
- **Risk if wrong:** none visible.
- **Overturn:** a rest-state ᚠ symbol with the alpha baked into its colour.
- **Status:** merged with D-243.

#### 150: iOS's sign-in hero is its Section's header

- **What:** the iOS sign-in hero moved from a Form row to the header of an empty first Section (headers are not
  clipped), 35 pt from the top on iOS 27, VoiceOver not announcing it as a heading; Android's is unchanged.
- **Why:** the row's clip removed Norse's glow and notch on iOS while Android showed them (a twin defect).
- **Risk if wrong:** the 35 pt was measured on iOS 27 only; the refused case's spacing is unmeasured.
- **Overturn:** back to a row (the twin difference returns).
- **Status:** merged with D-243.

### The D-244 chain, 151 to 158: settings follow the account (U25, ADR-0043)

#### 151: a setting is a small personal record; the cloud and old phones need nothing new

- **What:** each synced preference is one `setting` record (key and value as text) in the account's own log, record type
  3, header version 1; each key keeps its latest write. No Firestore rules change, no cursor reset: a 0.5.0 phone stores
  the record, cannot read it, and loses nothing else.
- **Why:** the rules check only the type, version and size; key and value as text never break an older build's decode.
- **Risk if wrong:** setting records join what the claim checks read under their 2,000 cap (noted for the Fable audit,
  CLOUD-SECURITY G-31).
- **Overturn:** a dedicated document per account (a rules change).
- **Status:** merged with D-244.

#### 152: seven preferences follow you; security and device things stay on each phone

- **What:** theme, light or dark, text size, Less motion, haptics, the app icon choice and the default currency sync
  (Less motion and currency only once chosen); the app lock and its timeout, screen privacy, the sync switch,
  notifications and the device name never do.
- **Why:** your list; a lock timeout synced from another phone could switch a lock off from afar.
- **Risk if wrong:** none known.
- **Overturn:** add or drop a key in the table (both shells, one line each).
- **Status:** merged with D-244.

#### 153: the account wins when a phone signs in, and the first phone to update fills the account

- **What:** at a phone's first sign-in (judged at its first server snapshot) the account's values are applied; keys the
  account lacks are filled from this phone's own choices. For phones already signed in when 0.5.1 arrives, the first to
  update fills the account. Signing out clears the catch-up marks, so signing in again reconciles.
- **Why:** the account is the shared truth; a new phone should look like your others at once.
- **Risk if wrong:** two phones updating at the same moment fill keys from both (they converge, latest write per key).
- **Overturn:** the phone wins at first sign-in.
- **Status:** merged with D-244.

#### 154: an arrived icon waits for SKIPTI to come to the front

- **What:** your rule; on iOS "the front" is the app active and unlocked, also the first server snapshot after it comes
  to the front (so an icon chosen on another phone can switch right after a sign-in); on Android, a real return from the
  background (never a re-creation) or an unlock.
- **Why:** iOS shows its own alert when the icon changes; Android swaps launcher aliases; neither should happen in the
  background.
- **Risk if wrong:** an icon change right after a sign-in in front.
- **Overturn:** only the next return to the front.
- **Status:** merged with D-244.

#### 155: an old install's settled light-or-dark is not a choice

- **What:** D-232 stored "System" on installs from before it; that value no longer fills the account at first sign-in
  until it is tapped (a per-phone mark).
- **Why:** an old phone would otherwise push System onto your newer Aurora-dark phones (the review's M3).
- **Risk if wrong:** none known.
- **Overturn:** drop the mark.
- **Status:** merged with D-244.

#### 156: Android applies settings on one queue

- **What:** every Android settings vault and apply step runs on one serial queue (taps, arrivals, launch, sign-out).
- **Why:** two queues could interleave an arrival with a tap and leave the phone showing a value the account does not
  hold (the review's M4).
- **Risk if wrong:** none known.
- **Overturn:** none needed.
- **Status:** merged with D-244.

#### 157: an arrived light-or-dark change re-creates the Android screen in front, as a tap does

- **What:** not deferred to the next stop (the review's M7 option).
- **Why:** Android holds a configuration change for a stopped screen until it returns, so deferring it would relaunch
  the screen on return and, with the app lock on, ask to unlock every time; arrivals only come while SKIPTI is in front,
  inside D-232's in-front window, which adds no prompt.
- **Risk if wrong:** what you were typing on another screen is kept by most screens but not all.
- **Overturn:** `configChanges="uiMode"` with a lock-rule change (a security control: its own unit).
- **Status:** merged with D-244.

#### 158 (yours, by pop-up, 2026-10-20): the privacy page says "Your app settings" and is Version 2

- **What:** the line you chose, "Your app settings: theme, light or dark, text size, Less motion, haptics, app icon and
  default currency, so your other phones match.", under "With an account", and "Version 2, 20 October 2026".
- **Why:** the cloud now holds your settings; the page promises its version changes with the policy.
- **Risk if wrong:** the live page lags until 0.5.1's Hosting deploy (your yes, a CLOUD-SECURITY row that day).
- **Overturn:** your words.
- **Status:** merged in the repo; NOT deployed.

### Session 11's opening round, 159 to 176: your answers (pop-ups, 2026-10-20)

Mock-ups: welcome and profile https://claude.ai/artifact/4gSbSAQy4QmwtXwZ5UfKr2 (v1), Just me
https://claude.ai/artifact/FwwzuzRkyyxiuGvJKNMPCG (v1); briefs `.drafts/u9/brief.md`, `.drafts/u10/brief.md`,
`.drafts/u29/calls.md`. Every one is yours; listed so the chains that build them read whole. Overturn: your words.

- **159 (U9):** first run is shape A, a short guide then you (welcome and what's where, pick a look, your name, your
  picture, done), every screen skippable, no account word; **centred and spread out, far fewer words on the welcome
  screen** (yours: v1 "looks like an excel sheet").
- **160 (U9):** "Pick a look" stays, centred, and the whole screen takes the tapped theme at once, so the person sees it
  while choosing.
- **161 (U17):** the picture confirms through a sheet (big preview, the groups line when signed in, "Use this
  picture" / "Not now"); nothing saves or shares until it is confirmed. The name keeps "Save name".
- **162 (U9):** You > About SKIPTI gains "Show the welcome again".
- **163 (U10):** Just me ships WITH "All my spending" (Just me plus your share of every group expense) in 0.5.1, as a
  full-width "Your spending" section below Your ledgers on Home (never on the balance card), with a Just me / All my
  spending switch, and a switch to show or hide it on Home.
- **164 (U10):** the tile is ᛁ in Norse and your picture or initial in Aurora and Vardr Labs.
- **165 (U10):** Home keeps its hero; with no group yet the hero asks the person to create one (if they want to).
- **166 (U10):** the CSV export gains a `category` column, appended last.
- **167 (U29):** Settle up gains "Another amount" under each card (prefilled with the debt, capped at it).
- **168 (U29, D-221 a and b):** "all settled" only when the whole group is; when you owe nothing but others still owe
  each other: "You're settled in Flat 4B" / "You don't owe anything here."; the payment count follows the
  fewest-payments plan.
- **169 (U29, D-221 h):** on a phone with no passcode the ring drops the app-lock step (comes back once one is set).
- **170 (D-210 o):** a display-only "this phone made this group" fall-back until the server's roles answer; the
  Functions still decide every action; no rule changes.
- **171 (GUARANTEES #5):** clarified: "Sharing a group with other people's phones needs an account, because shared
  groups live in the cloud for everyone in them." Nothing about the app changes (ADR-0013's fork, taken by you).
- **172 (U33):** the video is made as a Motion artifact (claude.ai; real recordings and screenshots; you export the MP4);
  nothing installed.
- **173 (U32, U33):** the sample "you" in public shots is "Sam".
- **174:** the sweep's other calls stay parked after 0.5.1 (own iOS dialogs, real FX, picking groups at sign-in, Night,
  iPad, Vardr Labs tab icons).
- **175 (U32):** the nine store captions as drafted in `.drafts/u29/calls.md` Part 3, plus "Your own spending, next to
  your groups." for a Just me shot.
- **176:** build with the drafted words, adapted to these answers; the revised pages show the final set, and you can
  redirect from them.
- **192 (U9, chat after v2):** the welcome's step indicator becomes a larger progress piece that advances as each step
  is completed; in Norse its runes light and glow one by one in step with the fill; Aurora and Vardr Labs get their own
  versions (a glowing gradient line with nodes; a bracketed segmented bar).
- **193 (U10, chat after v2):** the Ledger choice page gets richer rows: each ledger's tile and a meta line (when it
  was created, month and year, and how many people; Just me says so), not a flat list.
- **194 (chat after v2):** a fluidity sweep: every action that jumps instead of moving gets a fitting transition, in
  its own unit after U10 and before the store pages and the video; every animation passes the motion doors (Reduce
  Motion, Less motion, power saving for art).
- **195 (Norse art, chat):** the glowing runes glow from the glyph itself (a blurred copy of the rune's own shape under
  it), not a round halo, everywhere: the page's rising runes, the hero runes, the hero ᚠ, the You ring and the
  welcome's progress. Its own unit right after D-245 (`.drafts/runeglow/plan.md`). Also: the welcome's Norse progress
  runes must sit centred on the line, the last inside the bar's end (sent to D-245's implementer).
- **197 (mine, from your brainstorm ask):** Norse's welcome progress is "the winding road": five runes ᚠ ᚢ ᚦ ᚨ ᚱ (the
  futhark's opening: finishing the welcome spells it), joined by arches that alternate over and under, a gold fill with
  a spark at its front, each rune lighting from its own glyph as the road reaches it. Chosen over plain Bifröst arches
  (calmer but static-looking) and a runestone serpent band (most ornate, too busy at 26 pt). The three:
  https://claude.ai/artifact/L6fTyyxHynd8vBg9mivDf7. Overturn: say A or C. **Confirmed by you in chat (2026-10-20): "the winding road looks great".**

### The D-245 chain, 177 to 181, 198, 199: the welcome and the picture confirm (U9, U17)

#### 177: the welcome waits on its own flag, written at the first launch

- **What:** a new install's first launch writes `welcome/pending` (before D-175's `onboarding/complete`); the welcome
  shows while it is set and clears on Skip or Done. An install from before this build never gets it. A relaunch with
  it still set (the app was killed mid-welcome) shows it again from the start, after the app lock.
- **Why:** `onboarding/complete` is set the moment the vault opens, so it cannot tell "finished the welcome"; existing
  installs must never see a first-run guide.
- **Risk if wrong:** someone who killed the app mid-welcome meets the lock before the welcome (the lock is theirs).
- **Overturn:** clear the flag at the first Next instead.
- **Status:** in D-245.

#### 178: in first run the picture step is itself the confirm

- **What:** W4's page shows the staged picture and "Use this picture"; the Profile page shows the same confirm content
  in a sheet (161). Nothing saves before the button on either.
- **Why:** a sheet over a step that already exists to choose a picture would be a third layer for the same choice.
- **Risk if wrong:** none known; the component is shared.
- **Overturn:** present the sheet in W4 too.
- **Status:** in D-245.

#### 179: your name is at most 60 characters, refused in words

- **What:** the Your name field refuses (never cuts) over 60 characters, the cloud rules' share limit; errors read
  "Add a name first.", "Names can be up to 60 characters.", "That name has a character SKIPTI can't use." instead of
  core's raw text. Core's 64 for other members' names is unchanged.
- **Why:** a 61 to 64 character name was saved on the phone and silently never shared with your groups.
- **Risk if wrong:** none known; no existing name changes.
- **Overturn:** raise the rules to 64 (a rules deploy).
- **Status:** in D-245.

#### 180: the welcome's last line names Just me only once Just me exists

- **What:** W5 reads "Start a group for a trip, a flat or a dinner."; U10 appends ", or keep your own spending in Just
  me." (your approved line) in the same unit that builds Just me.
- **Why:** no build should name a ledger that is not there, even between two units.
- **Status:** in D-245; completed by U10.

#### 181: promise 5 follows your clarified guarantee everywhere it is shown

- **What:** GUARANTEES.md #5 (your decision 171), About's promise 5 on both phones ("...; sharing a group with others
  needs one.") and CLAUDE.md 10's summary.
- **Status:** in D-245.

#### 198: the welcome has Back on its middle steps

- **What:** W2 to W4 show a back control at the top left, opposite Skip; it goes one step back and undoes nothing; the
  progress never moves backwards.
- **Why:** found on the look: a mistaken Next had no way back but Skip.
- **Status:** in D-245.

#### 199: no toast inside the welcome

- **What:** the welcome's saves show their done row and play the success haptic, but post no D-224 toast; Profile
  keeps its toast.
- **Why:** found on the look: "Name saved" showed twice on one screen.
- **Status:** in D-245.

#### 200: one action at a time on the name step

- **What:** W3 shows "Save name" while the field holds an unsaved name and "Next" once it is saved (or empty); Skip and
  Back stay. W4 does the same with "Use this picture".
- **Why:** mock v2 drew one action at the bottom; two filled buttons on one step is the form look you disliked.
- **Risk if wrong:** someone who types a name and wants to move on without saving it must clear the field or Skip.
- **Overturn:** show Next beside Save name.
- **Status:** in D-245.

### The D-246 chain, 196: the runes glow from the glyph (your 195)

#### 196: the glow's numbers come from the floors, its shape from the glyph

- **What:** every glowing Norse rune (the page's rising runes, the hero runes, the hero ᚠ, the You ring's lit runes)
  draws a blurred copy of its own outline (Gaussian, sigma 0.18 x its size) under the crisp rune, cached once per glyph
  and size on each phone, never blurred per frame (the welcome's runes too). Alphas re-derived in motion.py: page 0.120,
  hero 0.345, ᚠ 0.305, ring 0.995, each the lesser of "the same light as the old disc" and what the text floors allow,
  with a glow landing on a neighbour's MEASURED ink (the review's I1: the first cut, 0.145, assumed D-243's smaller ink
  disc and failed 36 frames at textDim 4.400; 0.125 still fails 8).
- **Why:** your word on the halos ("not aesthetic"); the page glow is held down by the page-rune overlap scan, so the page runes stay subtle while the ring and the ᚠ show it clearly.
- **Risk if wrong:** the page runes' glow reads fainter than you want; raising it means loosening a contrast floor.
- **Overturn:** a larger blur at the same light, or (your call) a lower floor for the page.
- **Status:** in D-246.

### The D-247 chain, 182, 183, 201: Settle up's other amount and Undo, the settled words, the ring (your 167 to 170)

#### 182: Settle up's Undo is a held write, not a dialog

- **What:** a settle (whole or another amount) is held for 5 seconds: the card shows it settled, the toast says
  "Settled with {name}. No money moved." with Undo; Undo writes nothing ("Not recorded."); the write lands when the time
  runs out, at the next Settle, when Settle up, the group page or the removal flow goes away or starts, and when the app
  leaves the front (iOS inactive, Android pause or stop, never a configuration change). It is checked by core and by the
  ledger's own placing rules BEFORE it is held, so nothing can be refused after the card says settled. No confirmation
  dialog.
- **Why:** your "too many clicks" (feedback item 10) and guarantee 2's spirit (no interstitials); settlements have no
  revision path, so a true after-the-fact Undo would need a new record shape in core and the rules.
- **Risk if wrong:** the app crashing inside the 5 seconds loses a settle the person saw (they settle again).
- **Overturn:** a confirmation dialog, or a reverse settlement as Undo.
- **Status:** in D-247.

#### 183: another amount is capped at the debt, checked again at the tap

- **What:** "Another amount" refuses more than is owed ("That is more than {name} owes." / "That is more than you owe
  {name}."), against the live debt when Settle is tapped, so a partial never flips who owes whom.
- **Why:** an overpayment is a different event (the other person now owes); it deserves its own expense.
- **Overturn:** allow it with a warning.
- **Status:** in D-247.

#### 201: a screen reader gets a longer Undo

- **What:** with VoiceOver or TalkBack running the hold lasts 12 seconds and the toast's Undo is announced first.
- **Why:** the review found Undo nearly unreachable by a screen reader inside 5 seconds.
- **Status:** in D-247.

### The D-248 chain, 184 to 186, 202, 203: the small fixes (U29 to U31)

#### 184 and 203: a typed amount follows the phone's decimal mark; import stays strict

- **What:** every field a person types an amount into goes through core's `ffi_money_parse_typed(text, currency,
  decimal_mark)`: a comma is the decimal mark only on a phone whose mark is a comma; on a dot phone a comma is refused
  with "Enter an amount like 12.50."; on a comma phone a dot followed by exactly three digits ("1.234") is refused as
  ambiguous. Files (CSV import) keep core's strict parser, so "1,234" in a file is still refused.
- **Why:** a German keyboard could not enter an amount at all; the review then found the first cut read "1,234" as 1.234
  in the seven three-decimal currencies (KWD and others), 1000 times too low, without a word.
- **Risk if wrong:** a person on a comma phone who types "1.234" meaning one thousand is refused and retypes it.
- **Overturn:** accept both marks everywhere (the review's I1 returns).
- **Status:** in D-248.

#### 185: creating a group opens it

- **What:** after Create, the new group's page opens on Home's stack (Back lands on Home); the first group's big moment
  still plays over it.
- **Why:** the next thing a person does is add people or an expense there; landing on Balance hid it.
- **Status:** in D-248.

#### 186 and 202: bar titles stay between 17 and 20 points on both phones, measured

- **What:** pushed and sheet titles scale with text size between 17 and 20 pt (what iOS 26 and later draws itself;
  Android clamped to the same, its bar pills too), and a width table on each
  phone measures every title against the room beside the back button, and sheet titles against the room beside a Cancel
  that grows with text size, in every theme at every step.
- **Why:** iOS Norse cut "APPEARANCE" at step 2 (also a stale title width, fixed); the first cut capped iOS at 23 and
  Android at 22, but iOS draws at most 20 itself, so Android's larger titles were a visible twin difference.
- **Status:** in D-248.

#### 204: iOS bar buttons stay between 17 and 20 points too

- **What:** every text button in an iOS bar (Cancel, Done, Save and the rest) goes through one modifier that keeps its
  text between 17 and 20 pt, as Android's pills do; a test hosts a real SwiftUI sheet bar and measures what it draws.
- **Why:** the look found iOS's Cancel growing to about 44 pt at the largest step (SwiftUI buttons ignore the bar's
  font), pushing "New group" off centre while Android's stayed put: a twin difference.
- **Status:** in D-248.

### The D-249 chain, 187 to 191, 205, 206: Just me and All my spending (your 163 to 166, 193)

#### 187: one core function for both views

- **What:** `ffi_spending` returns a month's totals per currency, the category slices and the rows for Just me or All my
  spending; both phones call it; one golden table (`.drafts/d248/goldens.py`).
- **Why:** no money arithmetic in the shells (CLAUDE.md 10); one rule, one test table.
- **Status:** in D-249.

#### 188: no "sync" word on the empty spending page

- **What:** the empty page says "Things you pay for yourself go here. Nobody owes anybody." with or without an account.
- **Why:** guarantee 5: a screen without an account never mentions syncing.
- **Status:** in D-249.

#### 189: the spending switch and "Spending on Home" stay on each phone

- **What:** the Just me / All my spending choice and the Spending on Home switch are local preferences, not synced
  settings.
- **Why:** adding a synced key changes the privacy page's list (public copy and a Hosting deploy, yours).
- **Overturn:** add both keys to ADR-0043's table with a privacy line.
- **Status:** in D-249.

#### 190: the spending page is also reached from the Expenses tab

- **What:** the Expenses tab's Just me header opens the page (starting in Just me: 206), so it is reachable with the
  Home section hidden.
- **Status:** in D-249.

#### 191: moving an expense between Just me and a group is not in 0.5.1

- **What:** Edit shows the ledger as a plain value; to move one, delete it and add it again.
- **Why:** SPEC 5.3 wants an explicit, confirmed operation for a scope change; records are scope-bound.
- **Status:** residue of D-249.

#### 205: All my spending counts your share with each group's own "you"

- **What:** `ffi_spending` takes, per group, your id in that group (the id Balance uses); a group not listed counts
  nothing.
- **Why:** with one viewer list for every group, an id reused across groups (D-237's A1 case) could count someone
  else's share as yours (display only, but wrong).
- **Status:** in D-249.

#### 206: the Expenses header opens Just me

- **What:** the page opened from the Expenses tab's Just me header always starts in Just me; the Home card keeps its
  remembered view.
- **Status:** in D-249.

#### 207: the Just me export is named by the day it was made

- **What:** `skipti-just-me-YYYY-MM-DD` in the phone's own date (the file holds every Just me month, not one).
- **Why:** the review's M3: the plan's month name, in UTC, misdescribed a file holding all months.
- **Status:** in D-249.

#### 208: an expense's people must belong to its ledger before it is written

- **What:** a core check run on every write path before an expense is saved: the payer and everyone with a share are
  members of the expense's group (for Just me: you only). Ingest is unchanged (no core rule relaxed, no cursor reset).
- **Why:** the review's I1 found Android could write group A's expense with group B's people after the screen was
  re-created; `ffi_check_expense` never checked membership. Listed for Fable's security pass (a peer could still write
  such a record; the rules and ingest do not refuse it).
- **Status:** in D-249.

### The D-250 chain, 209, 215: the fluidity sweep (your 194)

#### 209: one motion vocabulary, pointing at what exists

- **What:** `ChangeMotion` (iOS `enum`, Android `object` read from the Swift file) names the UI changes: change 0.2 s
  (the switch's travel), number 0.2 s on the toast's ease, page 0.25 s, fill 0.9 s (the welcome's sweep), fade 0.15 s;
  helpers on both phones (rows moving, lines rising in, amounts rolling by core's integers, meters and the ring
  filling, pills sliding, the theme crossfade, buttons brightening, Android pages pushing and popping like iOS's).
  Every one is a cut under Reduce Motion or Less motion; power saving never stills them (they are moments, not art).
- **Why:** your sweep; no new numbers invented; a twin defect (Android pages cut where iOS slid) closed.
- **Status:** in D-250.

#### 215: Android's page slide ships; a real phone decides its cost

- **What:** Android pages slide on their own layers, the leaving page recorded once and its art held, one opaque ground,
  the tab bar outside the slide. On the host-GPU emulator a push still measures p90 27 to 32 ms against 20 to 21 with
  no slide, and the experiments (leaving page not drawn at all: 32; both pages as textures: 30 to 32; the slide cut:
  21 to 23) put the cost in any full-window slide on that emulator, not in our drawing.
- **Why:** the twin rule (iOS slides); the remaining cost is the test device's.
- **Risk if wrong:** a real Android phone stutters on every push.
- **Overturn:** a real-phone check at release (your device list): if it stutters, the page change becomes the 0.15 s
  crossfade on Android (cheap) and the twin difference is recorded.
- **Status:** in D-250; the real-phone check is owed.

### The D-251 chain, 216, 217: a store-shots seed for real screens

#### 216: store screenshots come from real screens, seeded in debug builds only

- **What:** `--store-shots` (iOS DEBUG builds, Android debug builds; the Android code lives in the debug source set)
  seeds a deterministic sample ledger (you are Sam; Goa trip, Flat 4B, Paris; Just me this month), through the normal
  checked store paths, no account, no cloud, no lock written; the theme from `--store-theme`.
- **Why:** your captions describe real features; mock screens would not be honest store pages.
- **Status:** in D-251.

#### 217: the seed never deletes a ledger it did not make

- **What:** the first run sets the existing vault aside (`ledger-vault.db.before-store-shots`, its key kept); a later
  run deletes only a vault marked `storeshots/seeded`; a vault with an account owner, or an unmarked vault while an aside
  exists, is refused with words and left alone; all moves are all-or-nothing; Android consumes the launch once. A
  restore recipe is in `.drafts/u32/plan.md`; the rule is throwaway devices only.
- **Why:** the review found a second run could delete a real ledger (CLAUDE.md 10: never auto-delete user data).
- **Status:** in D-251.

### The D-252 chain, 220 to 223: a deleted group ends on every phone (your 218, 219)

#### 220: the deleting account's own phones say "You deleted {group}."

- **What:** the `deleted` notice carries who deleted it; every phone of that account shows "You deleted {group}." (the
  date dropped from D-210's line); others keep "{by} deleted this group on {date}".
- **Status:** in D-252.

#### 221: a vanished group is checked with the server, every session

- **What:** at each session's first server listing, every group this phone holds that the account's listing no longer
  has (and that has no notice waiting) is read once from the server through the existing group read; refused or not
  found makes the copy read-only ("This group is no longer shared with you."); a network failure changes nothing; a
  phone-only group is never asked about; nothing local is deleted.
- **Corrected (fix wave, review M2):** "refused or not found" rested on a wrong premise, that only a member can read a
  group. The rules let a removed account `get` it (`uid in former`, firestore.rules:171-172), so a removal whose notice
  was lost read as "found" and the copy stayed writable. Now a read that finds the document makes the copy read-only
  unless this account is in its `memberUids` (`GroupGone.Read.former` / `notMember`), in the same neutral words (the
  remover's name is not in the document; the removal's notice, if it comes, rewrites the mark with it and catches up).
- **Fix wave (review M3):** the check runs only for a listing unlike the last one checked (`GroupGone.unchanged`: the
  same groups, each as confirmed), and on iOS reads the vault off the main thread (`GroupGone.see` in a detached
  task); Android already read on its serial thread and takes the same skip. A failed read, a failed lift or a read
  with no answer clears the remembered listing, so the next snapshot checks again.
- **Why:** it catches the groups 0.5.0 left writable and any that end while a phone is off.
- **Status:** in D-252.

#### 222: a vanished mark lifts if the server lists the group again

- **What:** the neutral mark is undone when the group is listed again (a false alarm guard); removed and deleted marks
  still lift only through an accepted invite.
- **Status:** in D-252.

#### 223: a write to a read-only copy is refused before it is kept

- **What:** every write path (expense, import, settlement and the settle hold, edit, group op, redistribution) refuses
  a copy with words before writing, the stored mark's words first; nothing is parked to fail later.
- **Why:** 0.5.0 saved such an expense, the cloud refused it, and it sat as "One change could not sync".
- **Status:** in D-252.

### The D-253 chain, 224 to 227: store-shot polish

- **224 (mine):** no edited pixels in store art; shot 9 uses the clean status-bar frames on both phones.
- **225 (mine):** store shot 6 uses the You page's rune ring, not the dimmed celebration frame.
- **226 (mine):** D-253 fixes what the first capture showed before the re-capture: the sample line, Exact split rows
  losing their names, Aurora's tile tones differing between the phones.
- **227 (yours, pop-up 2026-10-21):** Appearance's text-size sample "Lunch at Shake Shack" (your D-234 pick) becomes a
  neutral line: "Lunch with Luna" ($48.00 unchanged; Luna, a play on Runa, another repo of yours), both phones and the store shot.

### Going public and the approval gate, 228 to 231: your answers (pop-up, 2026-10-21)

- **228:** an approval gate for accounts, built NEXT session before Fable's pass (D-254): anyone can sign in, but the
  cloud (sync, groups, invites) waits until you approve the account; their own ledger works fully meanwhile (guarantee
  5); an honest "Waiting for approval" line in the app; the rules and Functions refuse cloud writes until approved.
- **229:** you approve through a tiny private admin page on Firebase Hosting (its own site, not skipti.vardrlabs.com),
  signed in with your Google account only; a Function sets the flag and accepts only your account. Deploying it is your
  yes on deploy day.
- **230:** Firebase App Check in 0.5.2, with guarantee 6 reworded to name Apple's and Google's attestation (a new SDK
  on both phones, written justification and audit).
- **231:** the public README gets curated photos of the app, which YOU pick from the store shots; the web version will
  be its own private repo, skipti-web, using this repo's core pinned to a tag.

### The D-254 chain, 239 to 251: the approval gate (your 228, 229, 233 to 235 and 252 asked for it)

- **239:** approval is a document, `approvals/{uid}`, written only by Cloud Functions; not a custom claim (a claim
  waits for a token refresh and needs a revocation to take back) and not a sign-in blocking function (a paid console
  upgrade that refuses sign-in itself). Risk if wrong: one extra document read per cloud write.
- **240:** the rules gate writes only: reading and deleting what is yours never waits for approval.
- **241:** six callables are exempt (signOutDevice, signOutEverywhere, requestDeletion, cancelDeletion,
  requestApproval, adminApprovals); a test reads every callable in the source and fails on any new one left ungated.
- **242:** the admin is a deploy-time setting, `SKIPTI_ADMIN_UID`, with no committed production value (empty refuses
  everyone), AND a Google sign-in; a refused caller is shown its own account id, which is how you set yours on deploy day.
- **243:** the admin page signs in without an SDK (Google's id token, then Firebase's REST sign-in, then the Function
  over fetch), keeps nothing in the browser beyond the sign-in round trip, and has an emulator mode only on localhost.
- **244:** an account with no profile (waiting or denied) keeps its deletion marker on its approval document, so the
  seven days and Keep my account work for everyone; the purge deletes the approval document with the account.
- **245 (server review):** the migration can run again and writes in transactions (a sign-up in the deploy-day window
  is not locked out; a denial is never overwritten); a denied phone may clear its own push address; the admin list is
  the newest 500 (a new index, READY before the Functions); denying drops the name and email.
- **246:** before the server has answered (the first launch after the update), the cloud stays closed but the screen
  looks as today: no "Waiting" flash for testers already approved; a cloud action asks the server first (5 s) and
  shows the offline words, never the waiting words, if it cannot tell.
- **247:** the approval toast shows only to someone who was waiting; an approved account starts silently.
- **248:** the approval listener runs while the app is open; each return to the front while not approved re-reads the
  approval from the server (a read, never a write), which recovers a missed update (seen on iPhone in the look).
- **249:** the denied invite page is titled "Not approved"; refused actions use the server's words.
- **250:** the emulator sign-in used for looks does not auto-approve (a look can show waiting); the proofs do.
- **251:** settings changed while waiting write no record (the first sync after approval fills them); saving your own
  name while waiting sends it again, so your approval page shows it.

### Public sample data is American, 232: your instruction (chat, 2026-10-21; first given in session 11 and lost)

- **232:** every public picture of the app (store shots, the film, README photos) shows American names and US dollars
  only: no INR, no rupee sign, no Indian names or places (you live in New York; that audience should see itself). The
  store-shots seed (D-251) changes on both phones and the shots and clips are captured again (D-255, after D-254 and
  before the film). The instruction was given in session 11 and never written down, so the re-capture kept Goa trip,
  Meera and the rupee; recorded here so it cannot be lost again. "Sam" (173) and "Luna" (227) stay.

### The approval gate and the American re-shoot, 233 to 237: your answers (pop-ups, 2026-10-21, session 12)

Mock-up https://claude.ai/artifact/4ULGr42bEn7YUhSu7pQaTx (v1); README pick https://claude.ai/artifact/J3EFygb5CUdhrU4wBa6ArU.

- **233 (D-254):** build the gate as the mock-up shows: "Waiting for approval" / "Not approved" on the You page's
  account row, the Sync card's early-testing words, one toast on approval, the admin page with Waiting / Approved /
  Denied and the one-time "Approve the N" migration. Design: `docs/superpowers/specs/2026-10-21-approval-gate-design.md`.
- **234 (GUARANTEES #5, ADR-0013's fork, taken by you):** the last sentence becomes "Sync requires an account, is on
  once your account is approved, and can be turned off at any time in one tap."
- **235 (privacy page Version 3, public on 0.5.1's deploy day):** a new item under "With an account": "Approval: while
  SKIPTI is in early testing, a new account waits for our approval before anything else is kept in the cloud. To
  decide, we see the name you show others, your sign-in email if Apple or Google gave one, and whether you use an
  iPhone or an Android phone."
- **236 (README, decision 231):** the README shows store shots 01, 04, 05, 07, 08 and 09, in their American re-shoot.
- **237 (store caption 2):** "Trips, flats, dinners. One page each." becomes "Trips, roommates, dinners. One page each."

- **238 (your ask, chat; my implementation):** public pictures at full real detail, never upscaled: README images
  composed so the app screen sits at its native 1320 x 2868 pixels (1:1, PNG); Android re-captured on a throwaway
  1440 x 2560 phone (was 1080 x 1920); App Store stays at Apple's fixed 1320 x 2868; the film renders without
  upscaling past the clips' native size. No AI upscaling or sharpening (it invents detail; 224: no edited pixels).
  Done in D-255 (`.drafts/d255/brief.md` section 5).

- **252 (yours, chat with a screenshot, 2026-10-21):** You > Profile and account: "Signed in with Google" and Sign out
  (with its line) become ONE card at the top; Find me (the username) moves right below it; then Sync, Notifications
  and the rest. Both phones. Built in D-254's branch (the same screen), after the look.

### The film, the README and the re-shoot's data, 253 to 257: your answers (pop-ups, 2026-10-22)

- **253 (D-255):** the American sample data has a New York flavour: Hamptons weekend, Apartment 4B, Brooklyn dinners;
  Sam with Emma, Jake, Olivia and Ben; US dollars only (`.drafts/d255/brief.md`).
- **254 (U33):** two films, 25 s each: the App Store preview in 9:16 with iPhone footage, the Play promo in 16:9 with
  Android footage (Play takes a YouTube link: your upload). No separate web film; the README uses photos.
- **255 (U33):** a wordless original score from the Motion type; the films must also work muted (store previews
  autoplay silent).
- **256 (U33, mine within your direction):** the end card reads SKIPTI, then "Even up. Cheers all round.", then
  "Vardr Labs" small. You liked "Even up. Cheer up." and asked for more cheerful and cool; the toast keeps your "Even
  up" and lands warmly for a group settling up. Overturn: one line.
- **258 (U33, the Motion form):** style "Runestone glow" (the real app screens float in the dark as carved runes ignite
  around them), colours SKIPTI Norse (near-black #07080E, gold #DFB043, parchment ink #F3EAD3, sea-green #7FD4C1).
- **259 (D-255, mine):** "Train tickets" is filed under Travel/Trains; in Brooklyn dinners Sam pays "Pizza night" and
  Ben "Dinner in Williamsburg" and "Tacos", so one group reads "You owe" (the shots show both directions, as v2's Paris
  did).
- **260 (D-255, mine):** the currency picker's promoted list (INR first, shipped behaviour) is not changed in this
  unit; no store shot or clip shows the picker. Recorded for a later look at the promoted order.
- **261 (yours, chat with a screenshot, 2026-10-22):** Home's "Your ledgers" cards wrap a long group name to two lines
  instead of cutting it ("Hamptons wee..."), both phones; built in D-255 before the store shots are finished.
- **262 (U33, mine):** the films are built and final: App Store preview https://claude.ai/artifact/2WKHKqqoZATSNEL9rWxsgU
  (9:16, iPhone) and Play promo https://claude.ai/artifact/Bp78HQrtXhEr1bY3R1FCVC (16:9, Android), 25 s each, seven
  beats (runes and the ᚠ, then Home, Add, Split, Settle, the theme flip Aurora to Norse as the climax, the end card).
  The theme beat stops before Vardr Labs (it shows only as a tile); Android's split clip is a softer 1080 x 1920 copy
  (the 18.9 MB original is over the upload limit). Nobody has watched them play yet: scrub both before exporting.
- **263 (yours, pop-up after watching, 2026-10-22):** the end card line becomes "Split the bill, not the friendship."
  ("Even up. Cheers all round." read robotic: 256 overturned).
- **264 (yours):** the score was not catchy; four scores on the same App Store cut so you can choose (stomp and clap
  folk-pop, Nordic drums and horn, bright synth pulse, warm lo-fi groove); the Play promo takes folk-pop until you pick.
- **265 (yours, after listening):** the score is the warm lo-fi groove, made grown-up (it sounded like a children's
  game) and dynamic: musical hits on the taps and actions on screen, a drop at the theme flip; both films take it.
- **266 (yours, 2026-10-22):** the score is parked: the lo-fi rework's clicks did not match and it sounded like a jazz
  app. You will bring a DRM-free, no-copyright reference track to show what you want; the films stay as they are until
  then (the picture and the end line are done).
- **257 (README):** a section "What it looks like" right after the intro, a 3 x 2 grid of shots 01, 04, 05, 07, 08, 09
  at full detail, each linked to its full-size file, no extra captions.

### Session 13's opening round, 267 to 278: your answers to Fable (pop-ups, 2026-10-22, the 0.5.1 security pass)

- **267 (disclosure):** 0.4.0 and 0.5.0 reached people outside Vardr Labs through TestFlight and Play internal
  testing, so they count as RELEASED builds for the CVE rule: an advisory whose flaw was in a build they ran gets a CVE
  request when the repo goes public.
- **268 (disclosure, public wording):** the credit line on every advisory reads "Found in Vardr Labs' pre-release
  security audit, carried out with Claude (Anthropic)." No model name.
- **269 (scope):** of the G items 7.1 deferred to "public", the cheap half comes into this pass before 0.5.1 deploys:
  G-17 (a per-author push cap), G-18 (the push line from checked fields), G-04 and G-23 (per-group daily quotas on
  invites and joins), G-26 (a signed-out phone's writes refused by the rules), G-13 (proof screens compiled out of
  release builds). The rest is done BEFORE THE 0.5.2 RELEASE, not at the app's public beta.
- **270 (G-05, your yes on the dependency, CLAUDE.md 13 rule 2 and CONTRIBUTING rule 4):** App Check with both SDKs
  (App Attest on iOS, Play Integrity on Android) is approved and scheduled for 0.5.2; enforcement in monitoring mode
  first. Not in this pass.
- **271 (G-22, your yes on the secret and the production rewrite):** keyed email fingerprints (a Secret Manager key,
  about $0.06 a month; a one-time rewrite of the stored fingerprints run with you on a deploy day) are approved and
  scheduled for 0.5.2. Not in this pass.
- **272 (G-06):** Firestore Data Access audit logs are switched on by you on 0.5.1's deploy day (a console step on the
  checklist, with a CLOUD-SECURITY section 2 row).
- **273 (fix policy):** every genuine flaw the pass finds in the code since D-237 is fixed now, before the deploy,
  whatever its severity: a unit each (tests, sabotage, gate, records, an advisory draft).
- **274 (G-01):** the API keys are restricted together with App Check before 0.5.2, with a third, browser key for the
  admin site then; nothing on 0.5.1's deploy day.
- **275 (Cloud Storage):** stays off. Nothing of SKIPTI's is in the file buckets (group sync is Firestore; pictures
  ride inside records since D-211), so deploy day deploys the Firestore rules only; `storage.rules` stays in the repo,
  emulator-tested, for the day a bucket exists. The D-254 design's "Storage rules" step is struck.
- **276 (option C, the tombstone):** built in this pass: `deleteGroup` writes a tombstone and the rules refuse creating
  a group document with a deleted group's id; the phones read the tombstone for a vanished group. Deploys with 0.5.1.
- **277 (production reads):** the read-only production checks read as D-237 did: configuration, structure, counts and
  consistency; ids and field names, never record payloads, names or emails.
- **278 (going public, U35):** the seven tracked files with absolute home-folder paths take relative paths; `deploy/`
  and `docs/DEPLOY-ORACLE.md` are removed from the public tree (vardrlabs/Skipti is the repo that goes public, never
  icrkdev/Skipti); the history files (BUILD-LOG, WORKLOG, ADRs, plans) KEEP their em and en dashes for now: the dash
  rule is for public-facing text, and you set it aside for these files ("maybe for later"). CLAUDE.md section 4's
  "sweep until the whole-repo scope passes" is therefore not a condition of going public.

### The 0.5.1 security pass's units, 279 to 316: D-256, D-259, D-257, D-258 and D-260 (Fable's pass, 2026-10-22; your 269 and 276 asked for them)

Numbered in the order the units returned: D-256 R1 to R7, D-259 R1 to R5, D-257 R1 to R12, D-258 R1 to R7, D-260 R1 to
R7. Source: each unit's report, `.drafts/fable/pass-051/dNNN/report.md`.

- **279 (D-256, Fable's pass):** a purged account's uid stays in a tombstone's `uids`, and purge's own deletion of a
  sole-member group writes no tombstone; why: `uids` is read only by the rules' get, a purged uid never signs in
  again, and nobody else holds a copy of a sole member's group; risk if wrong: a list of dead uids lingers in
  `deletedGroups`; overturn: strip the uid from tombstones in `purgeAccount`.
- **280 (D-256, Fable's pass):** the deleter is always in `uids`, even when missing from `memberUids`; why: an old
  group's creator may be absent from the member list, as `deletionAudience` already allows for; risk if wrong: none
  beyond one extra uid; overturn: drop the caller from `tombstoneOf`.
- **281 (D-256, Fable's pass):** a tombstone read with no answer counts as no tombstone, leaving D-252's neutral
  read-only mark, and that copy is not asked again; why: the group read already proved the account cannot use it, and
  read-only is the safe side; risk if wrong: a copy says "no longer available" rather than "deleted" for good unless
  the notice arrives; overturn: re-ask the tombstone on a later launch.
- **282 (D-256, Fable's pass):** no new refusal line, one new banner line "This group was deleted."; why: D-252's
  refusal lines fit, but its banner line needs a name the tombstone does not carry; risk if wrong: a nameless banner
  reads vaguer than the notice would; overturn: carry the name in the tombstone (it was left out on purpose).
- **283 (D-256, Fable's pass):** `Notices.deletedMark` may rewrite a nameless deleted mark that is not yours with the
  notice's name, never making a mark vaguer; why: the notice knows the deleter, the tombstone does not; risk if wrong:
  a late notice relabels a mark; overturn: keep the first mark always.
- **284 (D-256, Fable's pass):** `endIfDeleted` marks a copy only when a tombstone is readable; `.notMember` without
  one (another account's id, or the approval gate) behaves as before; why: only a tombstone proves a deletion; risk if
  wrong: a deleted group from before the deploy stays unmarked; overturn: treat `.notMember` alone as gone.
- **285 (D-256, Fable's pass):** the invite paths keep their existing refusal error for a deleted group; the
  read-only banner says why; why: no new error surface for a rare case; risk if wrong: an invite tap shows the generic
  refusal; overturn: map the tombstone to a dedicated invite line.
- **286 (D-259, Fable's pass):** the overflow isolation covers the add-up step, not only `balance_line`; why: with one
  record fewer every group's own line fits but the totals overrun and Home blanks the same way, and People sum across
  groups by name; risk if wrong: none found, the add-up is one more pass; overturn: isolate `balance_line` only.
- **287 (D-259, Fable's pass):** when a sum overruns, the group adding the most in the overrun's direction is marked
  (earliest on a tie), then the rest is added up again, one group per round; why: blaming whichever group tipped the
  sum depends on order and would mark an honest group sitting after the hostile one (the test puts Trip first); risk
  if wrong: an honest large group is marked beside a hostile one; overturn: mark every group in an overrunning sum.
- **288 (D-259, Fable's pass):** `narrow_sum` refuses exactly i64::MIN in `balance_line` and `settled_progress`; why:
  one member can make a victim's net exactly -2^63 (9,224 records, two creditors of 2^62), which fits i64 and which
  iOS's `abs` / `magnitude` reads of balance amounts would trap on (found by reading, not reproduced); risk if wrong:
  none, the value is never a real balance; overturn: let it through and clamp in each shell.
- **289 (D-259, Fable's pass):** a group whose lines fit but whose settled meter overflows keeps its balance line and
  draws no meter, and the card's "too large" line comes only from core's `overflowed` flag; why: one source of truth,
  and the balance core could add up is still true; risk if wrong: a missing meter goes unexplained; overturn: show the
  "too large" line for a meter overflow too.
- **290 (D-259, Fable's pass):** the per-phone setting prefix check (`applock.`, `privacy.`) ignores ASCII case; why:
  stricter at no cost, no shell applies a case variant, and refusing one closes it for any later build; risk if wrong:
  a legitimate synced key starting `Applock.` is refused (none exists); overturn: compare exactly. (The field name
  `overflowed` is the brief's own suggestion, recorded in the unit's report as its R6; not a separate decision.)
- **291 (D-257, Fable's pass):** joins and removals are charged to the group inside their own transaction, invites in
  a transaction of their own after the membership check and before the log read; why: a charge that commits only with
  the join or removal means a link holder or a plain member cannot use up a group's joins or removals with calls that
  fail, and no stranger can charge a group; risk if wrong: refused join and removal attempts are not counted per
  group; overturn: call `charge(groupLimitId(...))` before the transaction.
- **292 (D-257, Fable's pass):** adoptRemovals charges one removal per call, not per record; why: it is sole-member
  only and `REMOVALS_MAX` bounds its records; risk if wrong: a sole member adopts many removals for one charge;
  overturn: charge per record.
- **293 (D-257, Fable's pass):** notice lengths are UTF-16 code units, as the rules' `size()` counts them (measured in
  the emulator: 30 emoji pass, 31 refused; 61 euro signs pass), and both phones cut a long note at a whole code point
  of at most 59 units plus an ellipsis; why: iOS cut by grapheme (could exceed the rules) and Android by unit (could
  split a surrogate); now twins and never a lone surrogate; risk if wrong: an emoji cluster can be cut between its
  code points; overturn: cut by grapheme once both runtimes agree.
- **294 (D-257, Fable's pass):** the phone folds each run of control characters (C0, DEL, C1) and U+2028, U+2029 to
  one space before trimming; why: notes may hold line breaks, the new rules refuse them in a title, and a refused
  record would stall sync; risk if wrong: a multi-line note's push reads on one line; overturn: refuse such notes in
  the app instead.
- **295 (D-257, Fable's pass):** the amount is sent only when core's string is 1 to 40 units with no control
  character, else left out, and never on a delete; why: the rules and the Function refuse anything else; risk if
  wrong: a push without an amount; overturn: raise the bound in both rules and phones.
- **296 (D-257, Fable's pass):** the phones no longer read `namedGroups()` for notices; why: naming who did it was its
  only use there, and the Function now names the author; risk if wrong: none found; overturn: restore the read.
- **297 (D-257, Fable's pass):** the old `{text, expense}` and new `{expense, kind, title, amount}` shapes never mix
  (`{text, kind, ...}` is refused by the rules and by `push.notice`); why: one shape per record keeps both checks
  exact; risk if wrong: a hybrid client is refused; overturn: none wanted.
- **298 (D-257, Fable's pass):** a server profile name over 60 units reads "Someone", not cut; why: the rules cap
  profile names at 60, so a longer one is not a name the app wrote; risk if wrong: a legacy long name reads
  "Someone"; overturn: cut at 60.
- **299 (D-257, Fable's pass):** an emulator seam in onGroupRecord: under `FUNCTIONS_EMULATOR === 'true'` the built
  message and phone count are stored at `emulatorPushes/{recordId}` instead of calling FCM; no rule matches the
  collection and production never sets the variable; why: there is no FCM emulator and the suite makes no network
  call, so the name and cap tests need to see the message; risk if wrong: a misconfigured production writes a
  Functions-only document instead of sending; overturn: delete the four lines and keep only the pure tests.
- **300 (D-257, Fable's pass):** "logged once a day" is the `pushesCapped` mark on the author's counter, and records
  without a notice are never charged; why: one log line per author per day, and no cost for silent records; risk if
  wrong: none found; overturn: log every capped push.
- **301 (D-257, Fable's pass):** lead (d) stays residue: the push title is the group document's name, not the log's
  latest; why: `groupNameOf` needs a whole-log read per record and onGroupRecord reads only the group document; risk
  if wrong: a push titled with the name before a rename; overturn: keep the latest name on the group document.
- **302 (D-257, Fable's pass):** the emulator suite ran on ports offset by 10000 with its own `TMPDIR`, and the
  `firebase.json` edit was reverted before the commit; why: parallel units shared the Mac; risk if wrong: none to the
  product; overturn: not applicable.
- **303 (D-258, Fable's pass):** a phone whose entry was flagged signed out BEFORE this sign-in (a push that beat the
  registration's delete-and-remake) parks nothing and signs nothing out, and its records go up at the next push; why:
  the rules refuse its records until the entry is remade, so parking would be wrong, and signing out a phone that just
  signed in worse; risk if wrong: those records wait one extra push; overturn: kick a push after a successful
  registration.
- **304 (D-258, Fable's pass):** a phone-entry read with no answer parks as today; why: it keeps decision 114
  unchanged; a retry would be kinder but changes 114; risk if wrong: a signed-out phone parks a record it should have
  dropped; overturn: retry before parking.
- **305 (D-258, Fable's pass):** the approval is read first (D-254 M1), then the phone's entry, only on a
  PERMISSION_DENIED refusal that would park, once per one-by-one pass on both shells; why: the cheapest order that
  tells the two refusals apart; risk if wrong: one extra read per pass; overturn: read the entry first.
- **306 (D-258, Fable's pass):** one remote sign-out path: `signedOutElsewhere()` is the devices listener's former
  body on both shells and the push calls it; the registration path is left as it was; why: "the existing path" is then
  literally the same function; risk if wrong: none found; overturn: inline it again.
- **307 (D-258, Fable's pass):** a signed-out entry refuses every update except `tokenRemovalOnly()`, which also
  passes a lastSeen-only stamp when no token is left; kept, not tightened; why: it changes nothing but the time, keeps
  the flag (tested), and is the approval gate's helper; risk if wrong: a signed-out phone can still touch its lastSeen;
  overturn: refuse lastSeen-only updates on a signed-out entry.
- **308 (D-258, Fable's pass):** Android's new entry read is bounded at 8 s (`GroupEnsure.READ_TIMEOUT_MS`) to match
  iOS's `groupReadTimeout`, though Android's `serverApproval` stays unbounded; why: twins for the new read; risk if
  wrong: none; overturn: not applicable.
- **309 (D-258, Fable's pass):** the emulator suite ran on ports offset by 20000, `firebase.json` restored by copy
  before the commit; why: not to meet D-256 or D-257 on the 10000 set; risk if wrong: none to the product; overturn:
  not applicable.
- **310 (D-260, Fable's pass):** `BalanceUi` and `SettleUi` stay in src/main and only the proof screens move, to new
  files `BalanceScreenProof.kt` and `SettleScreenProof.kt`; why: the release report's condition ("after confirming no
  ordinary screen uses BalanceUi") does not hold (ExpenseDetail, ExpenseList, Settled, OverviewTab, SettleAll and
  AddExpense use BalanceUi; SettleAll and RemovalFlow use SettleUi; OverviewTab draws BalanceLineView), and new names
  mean no file exists in both source sets; risk if wrong: none found; overturn: split the shared types out first.
- **311 (D-260, Fable's pass):** `BalanceUi.computeLines` and `LedgerStore.loadGroupsOrSeed` become src/debug
  extensions, the seed reading and writing through the public `rawKvGet` / `rawKvSet` rather than widening the private
  `withVault` / `json`; why: call sites and tests unchanged, no private API widened for a proof; risk if wrong: two vault
  opens instead of one, proof only; overturn: make the private members internal.
- **312 (D-260, Fable's pass):** `HomeShell` goes from private to internal; why: the debug dispatch calls it for
  groups-proof and store-shots; risk if wrong: wider visibility inside the module only; overturn: route through a
  src/main entry point.
- **313 (D-260, Fable's pass):** `ProofLaunch.emulators` is called inside the kept `ProofArgs.emulators` guard in
  MainActivity and checks it again itself; why: the gate stays visible in src/main and the hook is safe on its own;
  risk if wrong: none, a double check; overturn: drop either check.
- **314 (D-260, Fable's pass):** the JVM source walkers include src/debug through one helper, `AppSources`, rather
  than per-site edits; StoreShotsTest's src/main-only walk and ApprovalTest's "helper not declared in src/main" read
  MAIN alone, deliberately; why: guards would otherwise silently stop covering the moved screens; risk if wrong: a
  guard meant for shipped code also scans debug code; overturn: per-site walks.
- **315 (D-260, Fable's pass):** the guard is one gate leg in `--full`, iOS and Android together, skipping where Xcode
  is absent; why: one leg, one list owner; Linux CI would otherwise fail on the missing binary; risk if wrong: Linux CI
  never runs the Android half; overturn: split the Android half into its own leg.
- **316 (D-260, Fable's pass):** dispatch words are matched as string-literal words, not raw text, and the `when
  (headless)` shape is banned instead of the six short words; why: `--store-shots`, `--emulators`, `--emulator-as` and
  `--welcome` legitimately stay in ProofArgs, and balance, settle, add, signin, export and import are route names
  elsewhere; risk if wrong: a proof dispatched by another shape slips past (the 23 names still catch the screens);
  overturn: list the short words with an allow list.

### The hand-back pop-up, 317 to 320: your answers (2026-10-22, the end of session 13)

- **317:** the pass's residues are accepted for 0.5.1 (record writes per group unlimited, a member can spend its own
  group's daily invites, G-26 narrowed); the per-group record cap goes with App Check in 0.5.2.
- **318:** SKIPTI-2026-003 gets a CVE request too (against the Functions code at the named commits), with 001 and 002.
- **319:** D-257's emulator seam in `onGroupRecord` (under FUNCTIONS_EMULATOR) stays.
- **320:** the same-day window for 0.5.0 phones (their pushes read "{name} changed an expense", their own deletes
  "{name} deleted this group" until they update) is accepted; D-254's order stands (Functions before the apps).

### The 0.5.1 deploy day, 321 to 326 (2026-10-24, session 14: your pop-ups 321 and 322; mine from 323)

- **321 (yours, pop-up):** the body-parser flaw (CVE-2026-111181, found by the deploy day's CVE sweep) is fixed BEFORE
  any Functions deploy today: D-261, then steps 3 and 6 deploy the fixed build.
- **322 (yours, pop-up):** the Firebase Apple SDK goes from 12.19.2 to 12.19.3 (Firestore's embedded trust store drops
  the compromised eMudhra roots) before the iOS 0.5.1 build: D-262. Build 8 stays Build 8 (nothing was uploaded).
- **323 (D-261):** the fix declares `@google-cloud/functions-framework` 3.5.1 exactly (the version Google's buildpack
  was already injecting) and relocks within the existing ranges, with no `overrides` and no framework upgrade; why: the
  runtime keeps the framework it has run since 0.4.0, the copy that serves the callables comes under our lock and the
  audit leg, and the ranges already take the fixed releases; risk if wrong: the buildpack treats a declared framework
  differently from an injected one (the deploy's build log and the audit's 401 checks read it back); overturn: an
  `overrides` entry without declaring the framework.
- **324 (D-261, D-262):** no SKIPTI advisory and no CVE request for either; why: each flaw is the upstream package's own
  (body-parser's CVE-2026-111181; the eMudhra compromise fixed in Firebase Apple 12.19.3), SKIPTI's code holds no flaw,
  and a second identifier for the same flaw would inflate the record (the disclosure rule); the exposure is recorded in
  CLOUD-SECURITY section 2 and BUILD-LOG; risk if wrong: a reader of the public advisories does not learn that 0.5.0's
  cloud ran an affected body-parser for two days; overturn: draft SKIPTI-2026-004 (advisory only, no CVE).
- **325 (D-261):** the crash is taken as real on the advisory and the 1.20.8 to 1.20.9 diff, although five payloads did
  not reproduce it on this Mac's Node 26.4.0; Node 22 is not installed to try (CLAUDE.md 13: no new tools); why: the
  fix is a patch release inside the declared ranges, cheap and safe either way; risk if wrong: none to the product;
  overturn: install Node 22 (your yes) and reproduce.
- **326 (deploy day):** your admin uid is read from the admin page itself: adminApprovals is deployed first with
  `SKIPTI_ADMIN_UID` empty (it refuses everyone), you sign in on the page, it shows "This is not the admin account." with
  your uid, and adminApprovals is redeployed with it; why: SKIPTI's sign-in asks Google for no email, so the console's
  Users rows cannot tell you which account is yours (the checklist's own fallback); risk if wrong: one extra Functions
  deploy of one Function; overturn: none needed.
- **327 (D-262):** that iPhones on 12.19.3 still reach Firestore is shown by key, not by a phone talking to production:
  the GTS Root R4 Firestore serves today has the same public key as 12.19.3's GTS Root R4, and the build's bytes show
  the new root store in both the debug and the Release binary; why: looks run only against the local emulators, which
  use no TLS, and a debug build is never pointed at production (incident 26); risk if wrong: Google changes Firestore's
  chain to a root 12.19.3 lacks and iPhones fail to sync (their TestFlight check is the backstop); overturn: a signed
  TestFlight build's first sync is the check (Phase F).
- **328 (yours, pop-up; D-263):** the sign-in email is described as each phone really sends it, today: the privacy page
  becomes Version 4 ("they tell us an account id. On an Android phone, Google also tells us the email address of the
  Google account you choose; on an iPhone we ask for no email and get none") and THREAT_MODEL.md's "What Firebase
  keeps" says the same; found on deploy day (iPhone asks `openid` only per ADR-0035, Android's Credential Manager token
  carries the email; Version 3 claimed every sign-in gives one, the threat model claimed none does). The twin gap, that
  an iPhone account can be found by username only while About on both phones says "username or email", goes to 0.5.2,
  where you choose the direction (iPhone asks for the email, or Android's is dropped).
- **329 (yours, pop-up, 2026-10-24):** the repository goes public today after all (Anthropic's CVP asks for "CVE
  credit, bug bounty records, or published disclosures"), with private vulnerability reporting on; the three
  advisories are published and their CVEs requested, each on your yes.
- **330 (yours, pop-up):** each advisory credits your GitHub account icrkdev as finder, with decision 268's line in the
  text ("Found in Vardr Labs' pre-release security audit, carried out with Claude (Anthropic)"); CWE ids 639, 340 and
  694 for 001 to 003; ecosystem "other".
- **331 (yours, pop-up):** a Fable session triages, for disclosure, every fixed issue from before the track began
  (D-235's Critical, High and Medium findings, D-259's overflow, the G-items closed in 0.5.1, older security fixes to
  builds testers ran) and drafts SKIPTI-2026-004 onward for the genuine ones (opener `.drafts/stop/fable-opener-5.md`).
  Until then the record says "three advisories published", never "every flaw disclosed".
- **332 (CodeQL, 2026-10-25):** the java-kotlin analysis of CodeQL's default setup failed on its second run ("could
  not automatically build any of it": its dependency caching restores `~/.gradle`, build cache included, so
  `compilePlayDebugKotlin` came FROM-CACHE and the extractor saw no compile); `android/settings.gradle.kts` now turns the
  LOCAL build cache off when `CI` is set (GitHub's runners set it), and this Mac keeps it; why: the smallest change, no
  repository setting to touch, no switch to advanced setup; no full gate, because a local build is unchanged by
  construction (`CI` unset) and `./gradlew help` evaluates the settings both ways; the proof is CodeQL's next run on
  main; risk if wrong: GitHub runners build Kotlin without the cache (slower, free on a public repository); overturn:
  advanced setup with `--no-build-cache`. Note: default setup analyses every push to main, `[skip ci]` or not.
- **333 (yours, 2026-10-25; 0.5.2):** the group order you drag on one phone follows your account to its other phones
  (a synced setting, as D-244's settings are), not per phone.
- **334 (yours; 0.5.2):** sorting groups by amount outstanding works per currency (groups ordered within their
  currency), with date created and the others beside it.
- **335 (yours; 0.5.2):** the 0.5.2 session starts with the admin page: a redesign, and the pipeline that tells you
  when someone signs up and waits for approval (Google Play's pre-launch robots told apart, so an upload does not
  ping you).
- **336 (yours; 0.5.2):** the app asks for notification permission once, as its own step right after sign-in or
  sign-up, saying why (so you do not miss expenses and group changes); it replaces D-196's "after creating an invite or
  joining a group". The Settings card stays.
- **337 (CodeQL triage, 2026-10-25; the founder: "fix them", and research the dismissals before acting):** of 16 open
  alerts, #1 to #4 (js/bad-tag-filter, js/incomplete-hostname-regexp, js/incomplete-sanitization, all in
  `firebase/tests/hosting.test.mjs`) are fixed: script tags matched in any case and form, every `<script` opening
  counted, a full regex escape; the sabotage shows the old join-page test let an uppercase `<SCRIPT>` through. #5 to #15
  (rust/hard-coded-cryptographic-value, "critical") are false positives, researched: the rule's nonce sink is a
  parameter-NAME heuristic (CodeQL's HardcodedCryptographicValueExtensions.qll: "any argument going to a parameter whose
  name matches a credential name"), and the only such parameters in core were two test fixtures; the value is a seed
  for the header label `nonce_session`, which no cryptography reads since D-200. The audit of shipped code found no
  hard-coded key, nonce or salt (the vault key from `random_bytes(32)` over OsRng; every fixed key array is in a test
  module; `VaultKey::from_slice`'s zero buffer is overwritten at once) and random sign-in nonces on every client
  (SecRandomCopyBytes, SecureRandom, crypto.getRandomValues). Step taken: the fixtures' parameter renamed `seed`, so
  the alerts close as fixed rather than dismissed, and no future test can trip the heuristic the same way. #16
  (java/android/insecure-local-authentication, the app lock not bound to a key) stays open for the Fable triage.
  Read back: CodeQL on daa22b00 marked #1 to #15 FIXED; it opened #17 on the new pattern (an end tag like `</script
  foo>`, which browsers accept, was not matched); the end tag now takes anything up to `>`, the sabotage with
  `</script\t\n bar>` turns the join-page test red, the emulator leg passes 335/335 (a test-only pattern: no heavy input,
  so no third full gate).
- **338 (yours, 2026-10-25; 0.5.2):** the app lock becomes key-bound on both phones (CodeQL #16): Face ID, fingerprint or
  the phone's passcode releases the vault key itself (iOS: the Keychain item under a `SecAccessControl` of
  `.userPresence`; Android: a Keystore key that requires user authentication, decrypted through `BiometricPrompt` with a
  `CryptoObject`), with the passcode fallback so a new fingerprint never locks you out of your data. Your concern,
  checked in the code: notifications are unaffected (the server sends the whole line as a `notification` payload; iOS
  shows it with no app code, Android's `onMessageReceived` uses only the payload; the vault is never opened to show
  one), and neither phone syncs in the background (no BGTask, no WorkManager): sync runs while the app is open, so it
  catches up right after the unlock. To design in the session: records that arrive while the app sits open past its
  lock timeout wait in memory until the unlock; turning the lock on or off re-protects the key; existing installs
  migrate once; tests on real devices; Fable reviews it.

### Siri and Gemini, 210 to 213: your answers (pop-up, 2026-10-21)

- **210 (U15, U16):** Siri and Gemini come in **0.5.2**, not 0.5.1.
- **211:** answers come from the phone only: the ledger on the phone and Apple's or Google's ON-DEVICE model; nothing
  leaves the phone; phones without an on-device model get the fixed commands (add an expense, what do I owe a person).
  Guarantees 6 and 7 stay as worded.
- **212:** Android may use Google's ML Kit GenAI (Gemini Nano, on-device API only) once CONTRIBUTING rule 4's written
  justification is done, its code read before the first build, Gradle checksums added and the Play manifest allowlist
  passes.
- **213:** the assistant is ON by default; the app lock still guards the answers (none while locked), and the
  disclosure line ("Answers come from this phone. They can be read aloud and shown on the lock screen.") sits in About's
  privacy section.
- **214 (pop-up, 2026-10-21):** a themed pull-to-refresh in **0.5.2**: our own indicator on both phones in place of
  Apple's spinner (iOS needs its own pull gesture) and Material's circle (Android's indicator slot); Norse's mark is
  **ᛃ Jera** (the year's turn: a cycle coming round), carving itself in gold as you pull, then glowing from its own
  shape and turning while it syncs, a short flare when done; Aurora a curtain-light arc, Vardr Labs a bracketed bar
  that fills; Reduce Motion and Less motion show a still lit mark.
- **218 (pop-up, 2026-10-21; your bug in 0.5.0):** a deleted group must end on every phone, the deleting account's other
  phones included: fix on BOTH sides: the `deleteGroup` Function also tells the deleting account (stored, not pushed),
  AND the apps confirm with the server when a group vanishes from the account's listing and, if it is gone, make it
  read-only with "Group deleted" (covers groups deleted before the fix). Root cause `firebase/functions/index.js:1479`
  (`members.filter((u) => u !== uid)`), `.drafts/groupdelete/rootcause.md`. Not a security flaw (the server refuses
  every read and write of a deleted group).
- **219 (pop-up):** the Functions fix deploys WITH 0.5.1's deploy, after Fable's pass (one deploy day; a CLOUD-SECURITY
  row then). Until then the 0.5.0 workaround is deleting the group again on the other phone.
---

## High: user-visible behaviour or a privacy surface

Ranked here, in full above: **31**.

### 27 (D-225): the vault-recovery erase also signs you out

- **What:** the confirmed erase on the vault-recovery screen also signs out of the cloud session on both phones, as
  the deliberate erases do.
- **Why:** the account's owner key lives in the vault; a session left behind would point at a ledger that no longer
  exists. The person can sign in again.
- **Risk if wrong:** someone erasing a broken vault is surprised to be signed out. Low; the screen already says
  everything goes.
- **Overturn:** drop the sign-out from that path. Small, both shells.
- **Status:** merged.

### 35 (D-227): role refreshes ignore stale answers and ask again when the group moved

- **What:** the role-asking rule on both phones ignores a refresh that ends after sign-out or after its group left, and
  asks once more when the group changed while a refresh was in flight.
- **Why:** without it a role change made during a refresh stayed wrong until the next change or a relaunch, and a new
  session could skip its first refresh.
- **Risk if wrong:** one extra `groupRoles` call now and then.
- **Overturn:** remove the two checks in `RoleAsking`. Small, both shells.
- **Status:** merged.

### 38 (D-227): a failed role check retries at 15 s, 60 s and 300 s

- **What:** a failed `groupRoles` call is asked again on a timer (15, 60, 300 s), as well as on the next change or
  return to the app, on both phones. Never after sign-out.
- **Why:** the look saw a creator shown as a member for 68 to 110 s when only the role call failed.
- **Risk if wrong:** at most three extra calls per failure; a stale answer could cancel a retry (follow-up Minor).
- **Overturn:** drop the timer. Small, both shells.
- **Status:** merged.

### 39 (D-227): while the server does not know the owner yet, the Owner pill follows 0.4.0's rule

- **What:** until the create uploads and the server knows the owner's place, the Owner pill follows the old account
  rule on both phones.
- **Why:** the review found the place answer could stay empty after the create landed, showing the owner as a member.
  Display only.
- **Risk if wrong:** a wrong pill for a few seconds on a two-phone account (the old bug's shape, briefly). No power is
  granted by it; the server decides actions.
- **Overturn:** show "member" until known. Small, both shells.
- **Status:** merged.

### 40 (D-227): a failed profile share retries at 15 s, 60 s and 300 s

- **What:** a `shareProfile` call that failed (Functions down) is retried on the same schedule as 38, not only at the
  next return to the app.
- **Why:** the re-look saw a share wait for the next foreground after the Functions came back. One rule for both.
- **Risk if wrong:** a privacy surface: your name and picture reach the group up to three times sooner than before,
  never anywhere new. Dropped on sign-out; not cancelled when the group leaves (one read per timer, follow-up Minor).
- **Overturn:** drop `ShareRetry`. Small, both shells.
- **Status:** merged.

### 43 (D-228): a money "-" is spoken as "none"

- **What:** where the money placeholder "-" stands for a value (the Settled tile, the money fallbacks), VoiceOver and
  TalkBack say "none"; the spoken money fallback is silent on both phones (Android read "-").
- **Why:** a screen reader saying "hyphen" or "minus" under Settled misleads; the final review found TalkBack could
  read a lone "-" as "minus" in 10 places (`.superpowers/sdd/d228/final-review.md`).
- **Risk if wrong:** "none" might read as "nothing owed" where the real cause is a core failure. Wording only.
- **Overturn:** a different spoken word ("no amount", "not available"). Copy change, both shells.
- **Status:** merged. Owed: VoiceOver and TalkBack on real phones (simulators cannot).

### The D-231 chain, 68 to 72: Less motion

You asked for a lower animation mode (2026-10-18): an in-app toggle aimed at lower-spec Android, built on D-214's still
frame. D-231 adds **Less motion** on Appearance, after Haptics, on both phones: the app's own Reduce Motion. Spec
`docs/superpowers/specs/2026-10-19-d231-less-motion-design.md` (its sections 3.4 and 6 still carry the first summary;
69 below is current), reviews in `.superpowers/sdd/d231/` (task reviews 1 to 4, final-review.md with its Confirmation).

Status for all five: **merged** (both phones); no cloud part.

#### 68: Less motion is the app's own Reduce Motion, in every place the app draws

- **What:** with the switch on, every place the app draws that honours the phone's Reduce Motion honours the switch:
  the theme art shows its designed still frame, the done moments and the settle celebration play their still versions,
  and the springs, slides and fades that snap under Reduce Motion snap (the tab bar, the bars, list rows, the bar
  transition on Home). It only adds stillness: the phone's own Reduce Motion and Low Power still hold the app still
  whatever the switch says. System-drawn dialogs, sheets, menus and ripples follow only the phone's own setting.
- **Why:** the app already has a designed still state for Reduce Motion (D-214), so the switch reuses it and every
  place that reads it, rather than a second, partial mode; an OR can never take stillness away from someone who asked
  their phone for it. The whole-branch review found Android's tab bar leaned on Compose's own animation scale and never
  saw the switch (I1); fixed in the unit, so the rule holds on both phones. "Every place the app draws" is its M4.
- **Risk if wrong:** someone who wanted only the art still finds the tab bar and bars stop springing too (the summary
  says "fewer animations"). One side effect, the iOS behaviour: under the phone's own remove animations, Android's
  selected tab no longer sits a little larger and a pressed + no longer sits a little smaller (before, Android only
  skipped their animation).
- **Overturn:** narrow the switch to the theme art. Small, both shells.
- **Status:** merged.

#### 69: on Appearance after Haptics, "Less motion" over "Still art, fewer animations"

- **What:** a twin of the Haptics row on both phones: a switch row with Lucide's `sparkles` tile (lucide-static 1.59.0,
  copied verbatim; NOTICE now lists twenty drawings), title "Less motion", summary "Still art, fewer animations". No
  haptic when it is switched; one accessibility element, the switch, named by its title and summary.
- **Why:** it sits with the other switch about how the app feels. The summary changed twice: the spec's "Holds the
  theme art still" undersold it (the switch also drops the tab bar springs, the bar slide and the re-tap scroll; the T2
  review), and "Holds the art still, fewer animations" truncated in Norse on your screenshot (the look measured the same
  on iOS in all five sets and on Android in Vardr Labs). You chose "Still art, fewer animations" (27 characters) by
  pop-up.
- **Risk if wrong:** someone looking for a speed setting does not think of Appearance.
- **Overturn:** move the row, or change the words. Copy or a small move, both shells.
- **Status:** merged; re-looked after the change on both phones (BUILD-LOG D-231).

#### 70: on by itself on a low-RAM phone, never by frame time

- **What:** until the person sets it, the switch reads ON on a low-RAM phone: Android's `isLowRamDevice`, or under
  3 GiB (3,221,225,472 bytes) of memory as the phone reports it (Android `totalMem`, iOS `physicalMemory`; iOS has no
  low-RAM flag). Read once per launch and never stored: the switch shows the effective value, and a tap stores a choice
  that wins from then on.
- **Why:** RAM class is deterministic, testable and visible as the switch's state; frame-time sampling would flip the
  app's look under the person after a measuring window and needs a sampler running in the very frames it judges.
- **Correction (the whole-branch review's M2):** phones report less memory than they are sold with, so phones sold with
  3 GB start with Less motion ON: on Android certainly, and on iOS very likely iPhone XR and iPhone SE (2nd generation),
  which run iOS 17 and 18. The spec said "false in practice on iOS"; that was wrong. Kept on purpose: those are the
  older phones the switch is for, and the person sees it on and can turn it off. The look's 2 GB Android emulator
  reported 2,537,912 kB and started ON.
- **Risk if wrong:** someone on a 3 GB phone gets a still app they did not ask for and has to find the switch.
- **Overturn:** lower the line (for example 2.5 GiB), or drop the default. One constant, both shells.
- **Status:** merged.

#### 71: one door per shell, held by source guards

- **What:** iOS reads the phone's Reduce Motion in two places only (the root's appearance applier and one function for
  code outside views); every view reads the app's own value. Android combines the phone's setting and the switch in
  one function, the only reader of the animation scale; the system's "animators enabled" call is forbidden, and every
  Compose animation in the app must pass the door (9 counted). Tests that read the source fail on a new direct read.
- **Why:** one place to change, and a new screen cannot quietly ignore the switch; the review's I1 showed Compose's own
  scale was a second, hidden door on Android.
- **Risk if wrong:** they are text guards. They miss an animation that reads nothing at all (as iOS's group sheets'
  `withAnimation`, which never honoured Reduce Motion), a Compose animation with no `label =` that borrows a later
  call's gate (C1), animation APIs not on the guard's list (C2; none in the app), and an iOS view outside the root,
  which would read "off" (M1; none today). They also fail loudly on unrelated names, never silently.
- **Overturn:** drop the guards; the doors stay. Tests only.
- **Status:** merged.

#### 72: not the Aurora scroll blur, not haptics

- **What:** the switch does not change the Aurora cards' blur cost while scrolling, and does not change haptics (their
  own switch).
- **Why:** the blur costs per card per scroll frame (decision 31); holding the art still does not remove it, the bake
  does. Haptics are not motion on screen.
- **Risk if wrong:** someone with a slow Android phone turns Less motion on and Aurora still scrolls as slowly until
  the bake ships.
- **Overturn:** make the switch also drop the glass blur (flat cards). Medium, both shells, and a look.
- **Status:** merged; the bake is its own unit.

### The D-232 chain, 73 to 78: Aurora dark for a new install

You decided on 2026-10-18 that a new install opens in Aurora dark on both phones before the final release (73). D-232
builds it with a launch migration that leaves every earlier install as it was (74 to 78). ADR-0042 "Dark by default"
records all six and amends ADR-0036's decision 1; ADR-0011 and ADR-0038 carry a note, and SPEC 11.1 too. Spec
`docs/superpowers/specs/2026-10-19-d232-dark-default-design.md` (section 6: the planner's changes, section 7: as
built, section 8: after the final review), reviews in `.superpowers/sdd/d232/` (task reviews 1 to 3, final-review.md
with its three confirmations).

Status for all six: **merged** (73 to 77 both phones, 78 Android only); no cloud part.

#### 73: a new install opens in Aurora dark (your call)

- **What:** with no appearance mode stored, both phones read Dark, so a new install opens in Aurora (the theme
  default, unchanged) with dark mode, whatever the phone's own setting. Light and System stay as choices on Appearance,
  unchanged, and a stored choice always wins.
- **Why:** your decision (2026-10-18, WORKLOG's list for the final release). Recorded here, not taken for you.
- **Risk if wrong:** someone on a light phone who wanted the app to match it has to choose System once.
- **Overturn:** yours to change; in code, `parse(nil)` back to System on both shells (one line each), and the
  migration (74) becomes moot.
- **Status:** merged.

#### 74: an install that ran before keeps System, through one launch migration

- **What:** the first thing the app does at launch (the first statement of `SkiptiApp.init`, the first after
  `super.onCreate` on Android), once per install: if no settled mark is stored, then if no mode is stored and the vault
  file exists, it stores `system`; in every case it stores the mark (iOS `appearance.modeSettled`, Android
  `modeSettled`, beside the mode, never in the vault). It asks only whether the vault file exists; nothing reads the
  vault. iOS writes the mode, then the mark; Android writes both in one `commit()`.
- **Why:** flipping the default alone would have turned every existing tester's app dark on upgrade, including people
  who never chose and are used to the app following their phone. A new install has no vault file at its first launch,
  so it stores nothing and reads Dark. The mark stops the migration on later launches: without it a new install's
  second launch, vault present by then and still nothing stored, would turn System. The T3 review found Android's
  asynchronous `apply()` could lose the mark if the process died at launch, with the same result; the unit writes it
  with `commit()` instead.
- **Risk if wrong:** a person whose preferences file is cleared while the vault stays reads System, not Dark (no
  current path does this); a vault file that will not open still counts as "ran before". One exception to "no
  change": an iOS restore of a pre-D-232 backup brings the preferences back without the vault (the vault is never
  backed up), so it opens Dark, on an empty ledger, as a new install would.
- **Overturn:** drop the migration, so everyone who never chose turns Dark on upgrade. Small, both shells.
- **Status:** merged.

#### 75: an unknown stored value reads Dark

- **What:** a stored mode this build does not know reads Dark, the default; a stored `night` (before D-212) reads
  Dark, as before.
- **Why:** an unknown value is best read as "no choice", and no choice now means Dark; it keeps one default, not two.
- **Risk if wrong:** a future build that adds a mode and is then rolled back shows Dark where System might be expected.
  Only a downgrade can produce an unknown value.
- **Overturn:** read unknown values as System. One line, both shells.
- **Status:** merged.

#### 76: ADR-0042 retires "light first" only as the default mode

- **What:** ADR-0042 supersedes "light first" in ADR-0011, ADR-0023, ADR-0038 and SPEC 11.1 only as a reading of the
  default mode. What light first was for stands: light mode is a requirement, designed and measured in every set that
  has one (Aurora light, Vardr Labs light), and every pair in every set is measured by the AppearanceTests on both
  phones; a light pair under its floor fails the gate as a dark one does. ADR-0036's decision 1 ("System (the
  default)") is amended.
- **Why:** your call changes the default, not the design rule, and since D-212 each set is its own measured column, so
  "dark derived from light" no longer describes how the sets are made. The older ADRs are left as written, with the
  new ADR saying how to read them.
- **Risk if wrong:** someone reading ADR-0011 or ADR-0038 alone takes light as the default.
- **Overturn:** edit the older ADRs too. Docs only.
- **Status:** merged.

#### 77: the launch screen follows the phone

- **What:** the launch screen, drawn before the app runs, follows the phone's own appearance, until the app has told
  the system otherwise (78, Android 12 and later). iOS's launch screen is empty and already did. Android's window theme is the app's own `Theme.Skipti`, light in `values` and dark in `values-night` (the
  platform's DayNight theme starts at Android 10, SKIPTI supports Android 8).
- **Why:** nothing can read the app's stored choice before the app runs, so no fixed colour is right for everyone: a
  fixed dark frame would flash dark at everyone who chose Light. Following the phone is right whenever the app matches
  the phone.
- **Risk if wrong:** every new install on a light phone shows a light launch screen for the whole cold start
  (typically hundreds of milliseconds, the whole-branch review's M1) before Aurora dark, and a dark phone opening an
  install set to Light a dark one. On iPhone always; on Android 12 and later only on a first launch (78), and on every
  launch below Android 12. The look measured about 1.2 to 1.7 s on a Debug iPhone simulator and 0.2 to 0.4 s on Android.
- **Overturn:** a fixed dark launch screen on both phones (a Light chooser then sees a dark one). Small, both shells,
  and a look.
- **Status:** merged.

#### 78: Android 12 and later is told the app's own night mode

- **What:** on Android 12 and later (API 31) the app tells the system its own light or dark choice with
  `UiModeManager.setApplicationNightMode`: Dark, and Norse (always dark), as night; Light as day; System as "auto",
  which clears the override so the phone's own setting reaches the app again. It tells at launch, right after the
  migration (74), and whenever the mode or the theme changes, and only when the value differs from what this install
  last told: Android has no public way to read it back, so the app keeps its own record (`nightModeTold`). The system
  keeps the value for the app, so the window, the text-selection toolbar and every later launch's splash follow the
  app. Below Android 12 nothing changes.
- **Why:** the look found a twin difference: in the dark app on a light phone, iPhone's edit menu was dark and
  Android's selection toolbar light (F2). One call fixes it and also gives Android 12 and later a splash that follows
  the app from the second launch, which supersedes the review's Android 13 option (`setSplashScreenTheme`). The differs
  check keeps a launch from looping: telling a new value makes the app's screen again once, and the new screen finds
  the record equal and tells nothing. The re-look proved it on a device (toolbar dark, the second launch's splash dark,
  Light, System both ways, Norse).
- **Risk if wrong:** a switch between light and dark makes the Android screen again once, as rotating does. The app
  lock read that as a fresh launch and asked again, at any timeout; D-232 fixed the lock so a change made in front is
  not leaving the app, and one commit of that fix opened without asking in one order of events (BUILD-LOG incident
  30), fixed before merge and proven on two Android versions. A lost record only re-tells the same value. The keyboard
  is the keyboard app's own and still follows the phone.
- **Overturn:** drop the call; the window theme and the splash then follow the phone (77) on every Android, and the
  toolbar difference returns. Small, Android only; the lock fix stays (rotation needed it anyway).
- **Status:** merged (Android only; iOS has no equivalent and needs none for its edit menu).

---

### 79 (D-233): the decision 31 blur bake is built, measured and not shipped

- **What:** option (d) of decision 31 (cards sample one offscreen bake of the shared blur instead of re-filtering it per
  card per scroll frame) was built on Android and A/B measured; it is not merged. Decision 31 stays at accept plus the
  real-phone check at the 0.5.0 gate.
- **Why:** p90 moved -0.17 ms (23.33 to 23.17, 12 runs each, alternated) against a run-to-run spread of 8 to 9 ms, and it
  costs 2.2 MB of GPU memory. Pixels were identical (max 1/255). Removing the card art entirely gained only 1.3 ms, so on
  today's tree the card blur is not a measurable cost on the emulator. Evidence `.drafts/d233/report.md`.
- **Risk if wrong:** a mid-range phone at 90 or 120 Hz may still pay a real per-card cost the emulator hides; then Aurora
  stutters while scrolling and the bake would have helped.
- **Overturn:** "overturn 79": apply `.drafts/d233/bake.patch` (about 50 lines; its guard test owes sabotage rows), best
  after the real-phone check shows Aurora's GPU p90 over one refresh interval or janky % 2 points over Vardr Labs, A/B on
  that phone first.
- **Status:** not shipped; nothing merged (D-233 is a measured no-ship).

## Medium: a twin or look choice with a visible effect

### 25 (D-225): "Added by" time follows the phone's 12 or 24-hour setting

- **What:** both phones (iOS already did; Android pinned 12-hour).
- **Why:** it is the person's own preference, separate from the en-US / en-IN date axis.
- **Risk if wrong:** two phones in one group show "14:05" and "2:05 PM". By design.
- **Overturn:** pin one format on both. Tiny. **Status:** merged.

### 26 (D-225): every section label takes `textDim`

- **What:** iOS's five labels that used `groundFaint` (People, Invite, "Split", New group, Your name) now use `textDim`.
- **Why:** a section label is not faint text, and Android already did this. The two tokens are equal in Aurora, so no
  contrast floor moves.
- **Risk if wrong:** labels slightly stronger in Norse and Vardr Labs. **Overturn:** five lines on iOS. Tiny.
- **Status:** merged.

### 28 (D-226): card borders drawn inside the shape

- **What:** on both phones; iOS straddled the edge, so Norse cards measured about 1 pt taller than Android's.
- **Why:** drawn size equals layout size on both; `strokeBorder` is iOS's idiom.
- **Risk if wrong:** half a point of border lost outside. **Overturn:** revert iOS's stroke. Small. **Status:** merged.

### 29 (D-226): the Norse hero's edge on the straight sides only

- **What:** like every Norse card and photo frame, not the diagonals, on both phones.
- **Why:** your Q18 pick (notch edge on the straight sides only) read as one rule; a hero with lit diagonals beside
  cards without them would look like two styles.
- **Risk if wrong:** the hero looks plainer than you pictured. **Overturn:** light the diagonals on the hero only.
  Small, both shells. **Status:** merged.

### 30 (D-226): iOS Norse page glow keeps faint bands

- **What:** 1-step bands visible only at 16x contrast; Android's is dithered.
- **Why:** four dithering attempts left the rings; the two that work cost 9 to 22 MB of memory for an invisible gain.
- **Risk if wrong:** a sharp-eyed user sees rings on a bright display. **Overturn:** take the 9 to 22 MB route.
  Medium, iOS only. **Status:** merged, recorded as residue.

### 32 (D-226): Android's Aurora dark bar band stays flat #53686A

- **What:** iOS's band is the system material and is not flat (#516567 to #55716F, #56706F beside the title);
  Android's colour sits inside that range.
- **Why:** chasing iOS's one pixel beside the title costs Android contrast (title 5.25:1 vs iOS's 4.78) for no visible
  gain.
- **Risk if wrong:** a side-by-side shows a hair of difference. **Overturn:** one colour value. Tiny.
- **Status:** merged.

### 34 (D-216): the app icon changes when Appearance closes

- **What:** never when the page is only covered (an alert, the app lock, a push). On Android the page also closes when
  the lock returns or a link opens; on iOS those only cover it. A tab switch cannot happen on a pushed page.
- **Why:** one system alert per leave, and the app never swaps its icon under you mid-choice. The look found Android's
  process survives the alias switch.
- **Risk if wrong:** a slightly different moment on the two phones when the lock interrupts. **Overturn:** apply on
  pick (more system alerts on iOS). Small to medium, both shells. **Status:** merged.

### The D-234 chain, 80 to 102: the twin fixes

The rule D-234 serves is yours (2026-10-15): the two phones implement the same behaviour, labels, order and copy, and
look identical; a visible difference is a defect unless it is truly impossible, and then it is recorded with the reason.
D-234 builds your two asks (A1, A2), the width test owed since D-231 (T1) and the looks' findings L1 to L14. Spec
`docs/superpowers/specs/2026-10-19-d234-twin-fix-design.md` (80 to 99; it was written before 100 and 101, and its line
on Space Grotesk is corrected in 82 below), reviews in `.superpowers/sdd/d234/` (task reviews 1 to 5, final-review.md
with T6's verdict and its confirmation of T8), T8's report `t8-report.md` (102). 83 and 84 are visible copy: yours to overturn in one line each.

Status: **merged** on both phones unless an entry says otherwise; four moved to 0.5.1 (86, 89, 93, 98), one no code
(85), one residue only (96); no cloud part.

#### 80: App icon is one row that opens in place

- **What:** your A2. One row, "App icon", with the current choice on the right ("Match theme", "Aurora", "Norse" or
  "Vardr Labs") and a chevron that turns down when open; a tap opens the four existing rows inside the same card, a
  second tap closes them. The "App icon" label above the card goes (the row says it); the caption ("Your icon changes
  when you leave this page.") shows only while open; the rows start closed every time the page opens. The opening
  fades the rows in and grows the card over the switch's own travel (0.2 s), and snaps under Less motion, Reduce
  Motion or Low Power. One accessibility element for the header with its state ("App icon, Match theme, collapsed").
- **Why:** a header row with a value and a chevron is the list's existing language (Default currency's row); the
  card, its hairlines and the motion value already exist, so nothing new is invented.
- **Risk if wrong:** you wanted the label kept, the caption always shown, or the open state remembered.
- **Overturn:** each is one line per shell (the label back above the card; the caption outside the `if`; store the
  open state).
- **Status:** merged.

#### 81: the sample line's amount follows the phone's own locale

- **What:** your A1 is "Lunch at Shake Shack", 48 US dollars for everyone, formatted by core. The locale is the
  phone's (`AppLocale.current`), not a fixed en-US.
- **Why:** both locales core knows (en-IN, en-US) print `$48.00`, so the line is the same for everyone today, and a
  locale core adds later formats the sample as it formats every other amount on that phone.
- **Risk if wrong:** a future locale prints US dollars differently (for example `US$48.00`) on that phone only.
- **Overturn:** pass en-US in `amount`, one line per shell.
- **Status:** merged.

#### 82: the width test measures every settings summary for real, on both phones

- **What:** one table of every fixed summary a settings row draws (You's six rows in every state, Haptics, Less
  motion, App lock, Erase, Delete account, Export, Import), measured in each theme's body face at the default size
  against the narrowest columns: a 375 pt iPhone (SE) and a 360 dp Android phone. iOS measures with Core Text; Android
  with Java's font layout on the same font files. A summary fits when its width plus 2 is inside its column. It
  replaces the two height tests, which could not see an ellipsis (that is how D-231's first summary shipped cut).
- **Why:** a summary that does not fit is cut with "..." and the row stays one line high, so only a width
  measurement catches it, and it must hold on the narrower phone of the two.
- **Corrected (D-234):** the spec said Vardr Labs' Space Grotesk "draws 400, a little wider" than the instance Android
  can measure (300). T1 measured the opposite: it narrows slightly as it gets heavier ("A tap when it is done" at 14
  pt: 142.10 at weight 300, 141.95 at 400, 141.75 at 500, 141.57 at 700), so Android's weight bound is a margin, not a
  correction; 101 tightened it.
- **Risk if wrong:** Android's real drawing differs from the font's advances by more than 2 dp, and a summary the test
  passes is cut on a phone.
- **Overturn:** an instrumented Android test on a device (new infrastructure; none exists).
- **Status:** merged.

#### 83: Haptics reads "A tap when it is done" (copy: yours to overturn)

- **What:** Haptics' summary on Appearance was "A tap when something is done"; it is now "A tap when it is done", on
  both phones.
- **Why:** the width test (82) found the old line did not fit a 360 dp Android phone (188.5 in a 178 column in Aurora
  and Norse, more in Vardr Labs). The new line keeps the meaning (the success tap plays on a save, a settle, an export
  and an import) and fits with room (130.7, and 141.9 in Vardr Labs).
- **Risk if wrong:** you prefer other words; the test then measures yours.
- **Overturn:** one constant per shell.
- **Status:** merged; the words are yours to change.

#### 84: You's account row names the sign-in method in one word (copy: yours to overturn)

- **What:** the "Profile and account" row on You reads "Google · 2 phones" (or "Apple", or "Email"), where it read
  "Signed in with Google · 2 phones". The Profile and account page itself still says "Signed in with Google".
- **Why:** "Signed in with Google · 12 phones" does not fit a 360 dp phone in any theme (213.2 in a 209 column); the
  row's title already says Profile and account.
- **Risk if wrong:** "Google" alone reads as a brand, not a state.
- **Overturn:** keep the long label and drop the phone count from the row instead.
- **Status:** merged; the words are yours to change.

#### 85: the "Fastest plan" order and the group colours were look-setup artefacts

- **What:** no code change for L3 (the two phones listed the Fastest plan's payments in a different order) and L11's
  avatar colours (one group blue on one phone, pink on the other).
- **Why:** both come from random ids: the look's two phones seeded the same story into separate groups, so a tie
  between two people owed the same was broken by different ids, and the colour (the id's first byte over six tones,
  already held equal by a parity test) differed. Two phones in one shared group hold the same ids and show the same
  order and colour; the paired look checks both that way.
- **Risk if wrong:** none in code; a separately seeded look will keep showing them.
- **Overturn:** fixed ids for the proof seeds on both shells, so separate installs match.
- **Status:** no code.

#### 86: Import and Review on the shared list design, moved to 0.5.1

- **What:** L2. iOS draws Import and Review as system forms and lists, Android as plain columns with Material fields,
  chips and a square checkbox; the words already match. A faithful twin moves both onto the shared list cards (as New
  group already is), with a drawn round check and a pinned action on Android.
- **Why not now:** four screens on two phones (about 300 lines), a new drawing (with NOTICE and the licence list) and
  its own look: a design pass, not a twin fix. 0.5.1 plan U5.
- **Risk if wrong:** 0.5.0 ships with the two phones' import screens looking different (the flow and words match).
- **Overturn:** build it before 0.5.0 as its own task.
- **Status:** moved to 0.5.1.

#### 87: honest empty lines when every group is read-only on this phone

- **What:** L7. With groups that cannot take a record from this phone, Add expense says "You can't add to your groups
  from this phone. Make a new group on Groups to add one." and Settle up says "Nothing to settle from this phone. Your
  groups here can't take new records." With no groups at all, the old words stay. iOS also hides its line under an
  error, as Android did.
- **Why:** the old words ("Make one on Groups first", "Add an expense with the + below") were wrong there; the new
  lines follow the read-only banner's own wording.
- **Risk if wrong:** the words.
- **Overturn:** two constants per shell.
- **Status:** merged.

#### 88: Android phones run portrait, as the iPhone does

- **What:** L14. SKIPTI's Android activity is portrait. Android 16 and later ignores this on large screens (smallest
  width 600 dp and up), so tablets and unfolded foldables still rotate.
- **Why:** the iPhone app has always been portrait only; landscape on a phone is a layout iOS never draws, so it can
  never be a twin, and in it the tab bar covered Balance's "Join a group".
- **Risk if wrong:** someone using a phone on a stand in landscape loses it. On Android 15 and below the lock applies to
  tablets too. D-232's lock fixes still matter (a theme or language change also re-creates the screen).
- **Overturn:** remove the attribute and design a compact-height tab root.
- **Status:** merged (Android only).

#### 89: the iPhone's large title after a re-tap, moved to 0.5.1

- **What:** L12 O2. A tab re-tap scrolls to the top; on iPhone the large title stays collapsed (UIKit scrolls only to
  the collapsed minimum), on Android it shows.
- **Why not now:** the fix needs iOS 18's scroll position API (with a fallback for iOS 17) or reaching into SwiftUI's
  scroll view; whether either makes UIKit grow the title back is unverified, and an experiment on the shell's scroll
  views does not belong in a twin fix. 0.5.1 plan U8, a spike gated on the simulator showing the title grow back.
- **Risk if wrong:** 0.5.0 keeps the difference (iOS lands one title height lower after a re-tap).
- **Overturn:** the spike before 0.5.0.
- **Status:** moved to 0.5.1.

#### 90: vault recovery breaks its lines as Android does; the width half is residue

- **What:** L5. Every paragraph on the iPhone's vault recovery screen breaks greedily, as Android's text does (iOS
  otherwise moves a word down to avoid a one-word last line), through the existing `GreedyText`, left-aligned.
- **Why:** with the same room the two phones then break at the same words. The rest is width: the look's iPhone had
  362 pt of room and the Pixel 7 371.4 dp, and the reason line (about 364.5) and Norse's title (about 368.5) fit only
  the wider phone. No layout should narrow the wider phone to the narrower.
- **Risk if wrong:** a greedy paragraph measures its width a frame late and draws the plain text first, as the erased
  title already does.
- **Overturn:** drop the greedy calls on this screen (one file).
- **Status:** merged (iOS); the width half recorded as residue.

#### 91: Groups' join ends as Balance's

- **What:** L6's twin. After joining from the Groups tab, every tab reads the ledger again and returns to its root, and
  the app shows Balance, as Android's join and Balance's own join do. The L6 fix itself (the iPhone's tab bar went
  missing after a join, a late "disappeared" from a replaced screen) is a bug fix, not a call.
- **Why:** on iPhone the join pages stayed pushed on the Groups tab after Done.
- **Risk if wrong:** none known; only the leftover pages change.
- **Overturn:** revert one closure.
- **Status:** merged (iOS).

#### 92: Android's Add expense title rests as the iPhone's does

- **What:** L4. On Android, Add and Edit expense's large title no longer rests half under the Cancel bar. After a drag
  that stops less than half way to the fold the page goes back to the top; past half way it goes on to the fold, where
  the bar's own title shows; a short form gets just enough room at the bottom to fold its title. Under Less motion the
  move snaps.
- **Why:** the iPhone's large title is UIKit's, which never rests half folded; Android draws its own.
- **As built (T6 rebuilt the brief's wiring):** the planned version had two real defects, found by the implementer.
  The extra bottom room fed back into itself (computed in composition from a height laid out under the previous room,
  it went 0, 16, 32, 32, 16, 0 and never settled), so it is measured in layout instead, with no state written. And the
  rest cancelled itself (its effect was keyed on "scrolling", which its own animation turns on, so it restarted from
  zero speed: a stuttering crawl), so it now collects the scroll state as a flow. Added: only a scroll a finger started
  comes to rest, so a field Compose scrolls into view above the keyboard is never moved back under it.
- **Risk if wrong:** a fling that stops near the fold jumps a few dp. Only Add and Edit expense have a large sheet
  title; the tab roots were not touched (no look found them resting half folded).
- **Overturn:** remove the effect and the extra room (one function); the page returns to its old behaviour.
- **Status:** merged (Android only).

#### 93: Add expense as a card over the page on Android, moved to 0.5.1

- **What:** L13. Add expense is a sheet card on iPhone and a full page on Android (D-179's recorded choice, before your
  rule that twins look identical). The fix is our own card over the shell on Android, with the page visible above it,
  not Material's bottom sheet (its animations cannot be snapped under Less motion, it is a second window for the
  keyboard and the Aurora backdrop, and it brings a drag handle iOS does not have).
- **Why not now:** about a day of shell work with keyboard, back and toast interplay, and a twin look of its own.
  0.5.1 plan U6.
- **Risk if wrong:** 0.5.0 keeps the page on Android.
- **Overturn:** build it before 0.5.0.
- **Status:** moved to 0.5.1.

#### 94: the iPhone dims a disabled button once, as a whole, as Android does

- **What:** L13. A disabled "Add expense" read dim green on iPhone and near black on Android. The iPhone's filled,
  outlined and tonal buttons now dim as one layer, so the label sits on the dimmed fill as Android's does.
- **Why:** SwiftUI dimmed each layer, so the label (already dimmed) sank into a fill dimmed too (about 1.75:1);
  dimming once is the app's own rule and Android's look (about 3.15:1).
- **Risk if wrong:** none known; the label gains contrast and no colour value changed.
- **Overturn:** three lines.
- **Status:** merged (iOS).

#### 95: the settle celebration's words sit on a card

- **What:** L10. The two lines of the celebration sit on the theme's own card (Aurora's glass, Norse's notched panel,
  Vardr Labs' frame), full width less 24 on each side, still at the same height, on both phones.
- **Why:** bare on the dimmed page they could land on the page's own words ("Where the money went"), worse on Android;
  on a card they never can, whatever the page holds.
- **Risk if wrong:** you wanted the words bare on the dimmed page.
- **Overturn:** drop one modifier per shell (or anchor the words to the Settled card instead).
- **Status:** merged.

#### 96: the Vardr Labs still frame and one sentence's wrap are width residue

- **What:** L11. The still frame's signs sit at the same fraction of the hero on both phones, but the hero is 370 pt
  wide on the iPhone 17 and 379.4 dp on the Pixel 7 while its words are fixed sizes, so the signs move against the
  words by up to about 7 units. "Marks this as paid here. It does not move money." wraps differently for the same
  reason (9.4 units of width).
- **Why:** the art is proportional by design (D-214); matching would mean a fixed frame that makes the wider phone look
  emptier.
- **Risk if wrong:** you read the sign over "3 groups" as a defect.
- **Overturn:** a fixed design frame for the field, in both motion files with new goldens.
- **Status:** residue (BUILD-LOG section 4).

#### 97: no press film on Android's +

- **What:** L12 O1. Android's + drew Material's ripple, which fades in at its own speed even under Less motion; it now
  draws none, as the iPhone's + never did. Its press scale and turn still follow the motion rule.
- **Why:** exactly iOS's look, and nothing left to gate. The other ripples (the back pill, the account glyph, rows)
  are not in the finding and stay; a ripple that honours Less motion is a 0.5.1 note.
- **Risk if wrong:** TalkBack and keyboard users lose a focus film on the +; the system's focus highlight still shows.
- **Overturn:** restore the ripple.
- **Status:** merged (Android).

#### 98: Android's text at the largest step, moved to 0.5.1

- **What:** L12. At the largest text step Android draws small text larger and titles smaller than the iPhone: iOS
  scales each style by its own ratio, Android one factor through its own non-linear table.
- **Why not now:** the twin is a per-style scale on Android with goldens for every style at every step, a typography
  change across every screen with a look at six steps in five sets. 0.5.1 plan U7.
- **Risk if wrong:** 0.5.0 keeps the difference at the larger steps (the default step matches).
- **Overturn:** build it before 0.5.0 as its own task.
- **Status:** moved to 0.5.1.

#### 99: expense rows: one 8 gap, a 14 card padding, the amount measured first

- **What:** L12. The iPhone's row puts 8 between the words and the amount (it had 32) and measures the amount first;
  Android's card pads 14 (it had 16). The rows then differ only by the phones' widths.
- **Why:** the iPhone wrapped "Meera paid ..." where Android kept one line; three layout values differed.
- **Risk if wrong:** 8 was not the intended gap (iOS's 32 was). What is left is width: the look found the iPhone (402
  pt) ending a long note in an ellipsis in Vardr Labs dark where Android's 411 dp fits it, recorded as residue.
- **Overturn:** three lines.
- **Status:** merged.

#### 100: Import's summary keeps its words, on three lines

- **What:** the Import row's summary ("Splitwise and Wallet by BudgetBakers exports. You see everything before it is
  added.") may take three lines on both phones, where it had two.
- **Why:** the width test (82) found two lines cut its last word on the narrowest phones; three fit at 375 pt and 360
  dp in every theme. The words name the two apps it imports from, so shortening them loses information.
- **Risk if wrong:** the row is a line taller than its neighbours.
- **Overturn:** shorter words and two lines; the test measures them.
- **Status:** merged.

#### 101: the width test's Vardr Labs bound is 1.01

- **What:** Android can measure Space Grotesk only at its default weight (300) and multiplies by a bound for the
  weight Vardr Labs draws; iOS proves the bound on every string. The bound is 1.01 on both shells, where the spec said
  1.05.
- **Why:** 1.05 drew a false cut on Android: "Still art, fewer animations" at 171.01 times 1.05 is 179.56 in a 178
  column at 360 dp, while iOS measures it at 171.7 at the drawn weight. The measured ratio is under 1.005 (and below 1
  for most strings, 82's correction), so 1.01 keeps a margin without failing a line that fits.
- **Risk if wrong:** a future summary whose drawn width runs more than 1 percent over the measured one passes on
  Android and is cut; iOS's proof of the bound would then fail first.
- **Overturn:** one constant per shell.
- **Status:** merged.

#### 102: Android's cards take the iPhone's inner spacing

- **What:** nine Android cards (Activity, People's card, search results and recent people, a group's Recent, Phones,
  and three cards in the removal flow) used the card's default inner spacing of 16 where the iPhone uses 14, 12 or 10;
  they now use the iPhone's. A test reads each value from the iPhone's code and requires the Android card to match, and
  every card with its own spacing on either phone must be one of those pairs.
- **Why:** the final review found it (its I1, from T6's concern): a 2 dp wider inset on each side, 6 on the recent
  people, visible side by side, so a twin defect by your rule, though not on the looks' list. The iPhone's values were
  the designed ones, and the Android twin of the expense card (99) had already moved to 14. T8's look measured 14.1,
  12.2 and 9.9 dp on Android.
- **Risk if wrong:** the iPhone's tighter values were the accident and Android's 16 the intent; then the cards read a
  little cramped on both phones.
- **Overturn:** the other way round, the iPhone to 16 on nine cards; the test then pairs the new values.
- **Status:** merged (Android only).

---

## Low: wording or internal

### 33 (D-216): Android's App icon caption loses its second sentence

- **What:** dropped "Some home screens remove the old icon; SKIPTI stays in your apps." Both phones read the same now.
- **Why:** the look found Launcher3 keeps the home-screen icon through the switch. The plan's own rule.
- **Risk if wrong:** Samsung One UI and Xiaomi MIUI are unverified; there the icon may vanish from the home screen with
  no warning. The 0.5.0 What to Test line covers it.
- **Overturn:** put the sentence back. Copy only. **Status:** merged.

### 41 (D-228): UniFFI's own dashes in the bindings, grandfathered

- **What:** moot. Your rule 42 narrowed the guard to the public side, so no baseline was committed.
- **Still true:** the 3 UniFFI template dashes in the generated bindings are on the whole-repo sweep list before the
  repo goes public. **Status:** recorded only, superseded.

---

## Open for you before 0.5.0 deploys

1. **31:** run SKIPTI's Aurora on a real Android phone and scroll; say if it stutters. Then look at the D-226 pairs.
2. **36 and 37:** confirm. They are access control and ship with the Functions deploy.
3. **44 to 52:** confirm that "only a backdated claim, or a pre-claim before someone joins, can take a history" is
   acceptable for 0.5.0, with the backdated
   claim (G-08) and `shareAliases` (G-31) going to the pre-release security audit.
4. **48:** the quota of 100 alias records a day; the audit re-checks it against real logs.
5. **45 and 52:** look at the read-only explanation and the "already in this group" line on a phone.
6. **43:** "none" as the spoken word for a missing money value.
7. **33:** if you have a Samsung or Xiaomi phone, switch the icon once.
8. **53 to 67:** confirm that "a server-marked place cannot be taken or denied, a copied id is a denial (66), unmarked
   places keep D-229's rules" is acceptable for 0.5.0, with the unmarked window, the second-phone race (F2) and the
   account-switch refusal (F3) going to the Fable security audit (G-08, G-31). And **55:** the quota of 100 held records
   a day, re-checked with 48 against real logs. And the copy pass: **64**'s "That changed on another phone." on an
   older link, and **66**'s reuse of "Someone has already joined as this person."
9. **68 to 72:** try Less motion on a real phone (Appearance, after Haptics), and confirm **70**: phones sold with
   3 GB (very likely iPhone XR and SE 2) start with it on.
10. **73 to 78:** on a real phone, install 0.5.0 fresh (it opens in Aurora dark) and over an earlier build you never
    set (it keeps following the phone); say whether **77**'s light launch screen on a light iPhone is acceptable; on an
    Android phone with the app lock on, rotate and switch Light and Dark (no prompt), then leave and return (prompt).
11. **80 to 102:** say whether **83** ("A tap when it is done") and **84** ("Google · 2 phones") are your words; on a
    phone, open Appearance's App icon row (**80**) and the text-size sample (**81**); confirm the four moved to 0.5.1
    (**86**, **89**, **93**, **98**) can wait, and that Android phones running portrait (**88**) is acceptable.
12. **103 to 128:** at the deploy (**126**), say when the rules go out relative to testers updating (the final
    review's minor 7); the release notes carry three lines: a reinstalled iPhone is signed out once (**107**), a 0.4.0
    edit with a negative Adjustment share or a note over 1,000 characters reverts (**103**), and the iPhone keeps an open
    sheet under the lock while Android returns to the main screen (**106**); say whether **115**'s two metadata
    refreshes (XML only, no version changed) sit right with CLAUDE.md 13; on a phone, look at **122**'s line and a
    reinstall (**107**); before a public beta, the capacity note (60 calls at once, 512 MiB doubles the Functions'
    GB-seconds).
13. **131 to 134 (D-237, the Fable audit):** read them; none is yours to decide, but they change the money path a
    day before the deploy. **134** lists what the audit leaves for 0.5.1; say if one should come forward. **135** is
    yours (Phase C dropped; the testers update on the day).

Not a decision, but owed at this milestone: the Developer Tools exemption goes off before any signed or store build
(CLAUDE.md 13).
