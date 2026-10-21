# WORKLOG — in-flight working state

**Read the NOW block first after any context compaction.** This file carries
what `docs/BUILD-LOG.md` does not: half-finished work, findings not yet
recorded as a D-row, the exact next step, and open questions. It is updated
after every meaningful step (CLAUDE.md §8). BUILD-LOG remains the permanent,
gate-checked record; this file is the working notebook.

Newest session log entries go at the bottom.

---

## NOW: 2026-10-20 (session 11, Opus; opener `.drafts/stop/opus-opener-6.md`): 0.5.1 U9+U17, U29-U31, U10, U32/U33

**RESUME (session 11, start):** main = 80c7a2d (vardrlabs/Skipti's starting commit), tree clean. Step now: the
founder's up-front round. Three Opus research agents write `.drafts/u9/brief.md` (onboarding + name/picture),
`.drafts/u10/brief.md` (Just me), `.drafts/u29/calls.md` (U29-U31 triage, his queued calls, store pages, video tool:
no ffmpeg on this Mac; Keynote and Affinity are). Then mock-ups as private Artifacts (U9/U17, U10, store-page style),
then ONE round of pop-ups for every call of his, then build in his order with decisions from 159.
- Briefs DONE: `.drafts/u9/brief.md` (findings: no first run exists at all; the name already has "Save name" + toast,
  only the picture saves on tap (with an account: one shareProfile per tap); core name max 64 vs share rule 60: a 61-64
  name is silently not shared (BUG, fix in U17); Android IME return does not save the name (twin defect); Norse chamfer
  is 10 since D-226, CLAUDE.md's "8" stale; GUARANTEES #5 says "Groups need an account" though local groups work: flag
  to him). `.drafts/u10/brief.md` (Just me = existing Expense record with group None = personal scope; core, sync,
  export already carry it; NO rules/Functions change, no deploy; shape A recommended = his 2026-10-13 decision).
- Mock-up builders (Opus) writing `.drafts/mock/u9-welcome.html` and `.drafts/mock/u10-just-me.html` from
  `.drafts/mock/spec.md`; I publish them. Video tool finding: the **Motion Artifact type** (claude.ai) composes real
  screen recordings and screenshots into a film and exports MP4 from its page: no install. Recommend it for U33.
- **HIS ANSWERS ARE IN (five pop-ups): decisions 159 to 176 in DECISIONS-TAKEN** (U9 shape A centred with few words;
  live-theme Pick a look; U17 picture confirm SHEET; replay row; U10 = Just me + All my spending NOW, a full-width
  "Your spending" section below Your ledgers with a Just me / All switch and a show/hide-on-Home switch; tile ᛁ Norse /
  picture elsewhere; hero kept, asks to create a group when none; CSV category column; Settle "Another amount"; "You're
  settled in Flat 4B" / "You don't owe anything here."; ring drops app lock without passcode; creator fall-back;
  GUARANTEES #5 clarified (his wording yes); video = Motion artifact; shots name "Sam"; parked calls stay parked;
  captions approved; build to the redraw, show him after). My own calls start at **177**.
- NEXT: redraw both mock pages to his answers (agent), meanwhile plan U9 (D-245, branch d245-welcome) and build.
- Mock pages v2 PUBLISHED (same links). Plans: `.drafts/d245/plan.md` (U9+U17; my calls 177-181), `.drafts/d246/plan.md`
  (D-246 settle + ring, D-247 small fixes; calls 182-186), `.drafts/d248/plan.md` (U10; calls 187-191). Decisions
  177+ get written into DECISIONS-TAKEN at each unit's record step.
- IN FLIGHT: D-245 stage 1 (iOS) implementer on branch d245-welcome in the main tree (report
  `.drafts/d245/stage1-report.md`); D-248 stage 1 (core only) in worktree `/private/tmp/skipti-d248-core` on branch
  d248-just-me from main (report `.drafts/d248/stage1-report.md`; no bindings until rebased after D-247).
- The founder after v2 (chat): decisions 192 (enlarged progress with runes lighting in step; sent to the D-245 stage 1
  implementer), 193 (richer Ledger choice rows: tile + "Since {Month Year} · n people"; in d248 plan section 8), 194
  (fluidity sweep = D-249, `.drafts/d249/plan.md`, after U10). Then (2026-10-20, ~21:25): **"I'll be away for a while:
  decide anything yourself after researching what fits best."** So: product and design calls are mine, recorded;
  deploys, consoles, money, anything public and GUARANTEES stay his explicit yes (opener's "Never" list unchanged).
- **D-248 stage 1 DONE 6b24891** (worktree /private/tmp/skipti-d248-core): `ffi_spending` (overview.rs), 
  `ffi_snapshot_personal` (ffi.rs), CSV 9 columns with `category` last ("Parent / Leaf"); core lib 394 -> 402; goldens
  `.drafts/d248/goldens.py`; 10 sabotage rows exact. Finding: `skrin d236_the_scanner_sees_a_key_freed_as_it_stands`
  failed once, passed on reruns (flaky: watch it in gates). Bindings after rebase onto main post D-247.
- D-249 audit (read-only) dispatched -> `.drafts/d249/audit.md`. DONE (top: Android pages cut where iOS slides; amounts cut;
  helpers ChangeMotion/PageMotion/MoneyChange/RowsMotion/Reveal/FillMotion; guards that go red listed).
- Founder (chat): rune halos -> glyph glow everywhere (decision 195, `.drafts/runeglow/plan.md`, own unit right after
  D-245); Norse welcome progress = "the winding road" (197, CONFIRMED by him; https://claude.ai/artifact/L6fTyyxHynd8vBg9mivDf7).
- **D-245 stage 1 DONE 9360fe2** (iOS; 894 tests 0 failures; report `.drafts/d245/stage1-report.md`). My look on
  d245-look (lock off via defaults `applock.timeout off`; DELETE the key / the sim at close-out): good; polish wave sent
  to the same implementer: P1 field tap target, P2 Norse card corners + double line in welcome, P3 Back on W2-W4
  (decision 198), P4 no toast inside the welcome, haptic kept (decision 199). Then stage 2 = Android twin (needs
  `TypeScale.welcomeMark = 52` on Android).
- Stage 1b 4bad2cd (iOS polish, 897/0); **stage 2 f1483ac** (Android twin, JVM 1078/0; look 23 shots
  `.drafts/d245/look/android/`; AVD d245-look kept). **Opus review: Needs fixes** (`.drafts/d245/review.md`: I1 Android
  stale welcome after erase, I2 "stays on this phone" lines shown signed in, I3 largest text unchecked + minors + twins).
  Fix wave dispatched (report `.drafts/d245/fixwave-report.md`). Decision 200 drafted. Then: sabotage lanes, pairs,
  gate, records (`.drafts/d245/record/decisions.md` holds 177-181, 198-200), merge, cleanup (d245-look sim + AVD).
- Fix wave c59c013 (iOS 903, JVM 1088); sabotage 50 rows (45 EXACT, 5 LISTED, 2 survivors pinned in e80825a);
  **gate 23/23 (2026-10-21T03:59Z, 5m21s; iOS 904, JVM 1089, cargo 406). RECORDED (BUILD-LOG D-245, decisions 177-181
  and 198-200, CLAUDE.md 10 guarantee 5). Merging.** Next: the rune glyph glow unit (`.drafts/runeglow/plan.md`), then
  U29-U31 (`.drafts/d246/plan.md`: settle + ring, then small fixes), then U10 (`.drafts/d248/plan.md`, core stage done
  in worktree /private/tmp/skipti-d248-core), then D-249 fluidity, then U32/U33.
- **D-245 MERGED 0408097, pushed (main and branch), no Actions run.** Cleanup: d245-look sim and AVD deleted
  (avdmanager needs `zsh -lic` for JAVA_HOME), AFAF4221 poster cache (1.3 GB) deleted. Sent him two Norse shots.
- **D-246 the rune glyph glow, branch d246-rune-glow from 0408097:** stage 1 (motion.py + iOS) implementer dispatched
  (report `.drafts/d246/stage1-report.md`); then stage 2 Android, review, sabotage, gate, records, merge.
  NOTE: the plan folder `.drafts/d246/plan.md` holds the settle/small-fixes plan (written before the glow unit was
  added): those units become D-247 (settle + ring) and D-248 (small fixes); U10 becomes D-249 (its plan and core
  stage live in `.drafts/d248/` and worktree branch `d248-just-me`), the fluidity sweep D-250. Folder names stay.
- **D-246:** stage 1 6e35618 (iOS + motion.py), stage 2 f03b0a7 (Android), review Needs fixes (I1 page cap from the
  measured receiving ink), fix wave 233dfba (page glow 0.120); gate 23/23 (4m13s); RECORDED (decision 196). Merging.
  NEXT: D-247 settle + ring (`.drafts/d246/plan.md` section "D-246: Settle up and the ring": read it as D-247).

## SESSION 10 (closed): 2026-10-20 (Opus; opener `.drafts/stop/fable-opener-3.md`'s successor): FINISH D-239, THEN 0.5.1 U27, U26, U24, U25

**THE REPO MOVED (2026-10-20, end of session 10, the founder's pop-up):** `origin` = **vardrlabs/Skipti** (private
until 0.5.1 makes it public), started from ONE clean starting commit (today's tree without the never-public files; the
old history held a personal and a developer address, a test Gmail address, CLOUD-SECURITY.md and pre-2026-09-18 Co-Authored-By
trailers, so none of it was carried); the full history to 5fde3ac stays in **icrkdev/Skipti** (private archive, remote
`archive`, branch `archive/main`, never pushed again). **vardrlabs/skipti-private** at `./private/` holds
CLOUD-SECURITY.md and SECURITY-ADVISORIES.md; `docs/` has git-ignored symlinks to them; commit them with `git -C
private`. CLAUDE.md sections 2, 3, 4, 6, 10 say so. The founder's to-do: set the vardrlabs Actions budget; repo
settings (secret scanning, branch rules) when he wants. security@vardrlabs.com is a group mailbox (his answer): it
stays in SECURITY.md.

**Security disclosure track (the founder, 2026-10-20, end of session 10):** for Anthropic's Cyber Verification
Program; rules in CLAUDE.md section 2 and 4 and the global CLAUDE.md; drafts `docs/SECURITY-ADVISORIES.md`
(SKIPTI-2026-001..003 from D-237's A1..A3, for Fable to complete); plan U34; a Fable pass before 0.5.1's deploy (the
opener says so). Facts checked: the repo is PRIVATE (GitHub's private vulnerability reporting answers 404; advisories and
CVEs are published for public repos), SECURITY.md and THREAT_MODEL.md exist; Anthropic told the founder directly that
they want to see legitimate work and use cases such as published disclosures and CVE credit (his words, 2026-10-20):
take that as the requirement. **The repo goes public WITH 0.5.1, moved to the vardrlabs organisation** (the founder,
2026-10-20): the checklist is the plan's U35 (security pass, the never-public sweep incl. CLOUD-SECURITY.md and its
history, the whole-repo dash sweep, the transfer, then public + private reporting + advisories).

**RESUME (session 10, close; read this first):** D-239 to D-244 are MERGED (main after f170165, pushed, no Actions
run, machine clean). The founder's session asks are DONE: D-239 finished; the runbook's 0.5.1 numbers (v5); the
exemption reminder given; U27 (D-240), U26 (D-241), U24 (D-242 colours, D-243 art), U25 (D-244 settings sync). OWED at
0.5.1: the privacy page's Hosting deploy before the apps (runbook Phase F for 0.5.1; his yes), the version bump at
release prep (decision 138), What to Test notes. NEXT (a new session, opener `.drafts/stop/opus-opener-6.md`), in the founder's
order (pop-up 2026-10-20, written into the plan's "Found by a sweep" section): (1) U9 onboarding + U17 name and picture
set-up (brainstorm with him first); (2) U29 to U31 small bugs and friction (incl. the iOS Norse text-step-2 title cut);
(3) U10 Just me; (4) U32 store pages (8 to 10) + U33 a cinematic video, Norse featured; (5) U28 web after the public
beta.

**RESUME (session 10, updated after D-243's merge):** D-239 to D-243 are MERGED (main 162f251, pushed,
no Actions run, machine clean, poster caches deleted). NEXT: U25 settings sync as **D-244** from `.drafts/u25/design.md`
(staged implementers: 1 ADR-0043 + core record/projection/FFI + bindings + switch arms; 2 iOS SettingsSync; 3 Android
twin; 4 emulator two-phone proof + the privacy line in `firebase/public/privacy/index.html` (no Hosting deploy without
the founder's yes) + looks; then Opus review, sabotage, gate, records). After U25: the rest of the 0.5.1 plan in its
order (`docs/superpowers/plans/2026-10-19-0.5.1-plan.md`), his calls collected. The bullets below are the history.

**D-244 fix wave DONE 2817fd2; privacy Version 2 (his pop-up: dated today) + hosting test pin; gate 23/23
(23:27Z); RECORDED (decisions 151-158); merging. OWED: the privacy page's Hosting deploy (his yes on 0.5.1's day,
CLOUD-SECURITY row) before 0.5.1's apps ship; the iOS Norse text-step-2 "APPEARAN..." title cut (twin defect, new
unit).**
**D-244 (U25) progress, branch d244-settings-sync:** stage 1 DONE a320635 (ADR-0043; core `GroupOp::Setting`,
projection `settings`, FFI `SettingOut {key, value, recordId, deviceId}`; bindings; switch arms both shells; cargo 406,
iOS 857/0, JVM 1035/0; report `.drafts/d244/stage1-report.md`). Stage 2 DONE 29f3508 (iOS SettingsSync; iOS 873/0, JVM
1035/0; 10 sabotage rows red; report `.drafts/d244/stage2-report.md`). Stage 3 DONE 60fe331 (Android twin; JVM
1052/0, iOS 873/0; 14 sabotage rows; report `.drafts/d244/stage3-report.md`). Stage 4 DONE 94e7622 (two-phone proof both ways;
cross-shell look works incl. icon at the front moment and sync off/on; privacy line local, NOT deployed; report
`.drafts/d244/stage4-report.md`; finding for later: iOS Norse at text step 2 truncates the Appearance title, Android
does not). Opus whole-branch review dispatched; then sabotage gaps, gate, records (decisions 151+), merge. Then stage 3 Android twin, stage 4 emulator two-phone
proof + privacy line (local only) + looks, review, sabotage, gate, records (decisions from 151: the U25 calls).

**RESUME (session 10, written mid-D-243):**
- **Merged and pushed this session (no Actions run):** D-239 8904cec (moments ignore power saving; decisions 137 to
  140, 140 = one named cargo-deny exception RUSTSEC-2026-0374); D-240 a50132b (U27 "Group deleted", iOS tab-root fix;
  decision 141); D-241 de09592 (U26 "Suggested" currency; decision 142); D-242 be62ae1 (Norse Vibrant colours; decision
  143: textFaint #9A8F76). Runbook v5 (0.5.1 = iOS 0.5.1 Build 8, Android 0.5.1 / 5001; bump at release prep).
- **In flight: D-243 (U24 motion half), branch `d243-livelier-art` from be62ae1.** Plan `.drafts/d243/plan.md`
  (T0..T12, 30 sabotage rows). Stage 1 (T0 to T3: motion.py, both engines and their tests) dispatched to an Opus
  implementer; its report goes to `.drafts/d243/stage1-report.md` and it commits on the branch. If the session died
  mid-stage: check `git log` on the branch and that report; a stage with no report and an unclean tree is redone from
  the last commit (look before discarding anything). Next stages: 2 = T4/T5/T6 renderers both shells + guard
  amendments + floor tests; 3 = T7 edge glow + gap audit; 4 = T8 look (d214-look, throwaway AVD d243-look); then Opus
  review, T10 sabotage, gate, records (ADR-0041 Motion amendment, CLAUDE.md 10 line, DECISIONS: hero lanes left of the
  ᚠ, halos as discs, ring upright each start, edge glow as bands, 1e finding), merge, cleanup.
- **D-243 stage 1 DONE 9c470f3** (motion.py sections 5-6, both engines + tests; iOS 849/0, JVM 1028/0; report
  `.drafts/d243/stage1-report.md`). Findings: page flare 0.19 sits at its cap; 1e page rune-on-rune overlap fails textDim
  (today worst 4.227 in ~5.5k frames/hour; with flare 3.039 in ~15k). **The founder (pop-up): GIVE EACH RUNE ITS OWN
  LANE** (16 evenly spread lanes, no two ever overlap; floor holds every frame). Stage 2 (T4/T5 renderers + guards +
  test 6 source checks) dispatched; stage 3 = the lanes (motion.py: lanes so 1e reports zero ink-on-ink and zero textDim
  failures, halo-on-ink measured; RuneDrift x formula both shells; goldens re-derived; the Look Book's rune layout
  changes by his call); stage 4 = T7 edge glow + gap audit (clear distance 7.00 pt); stage 5 = T8 look.
- **D-243 stage 2 DONE 337c3c1** (renderers both shells; iOS 850/0, JVM 1029/0; its sanity look caught a LAUNCH CRASH
  only a look could see: two `ForEach(0..<N, id: \.self)` in one Canvas `symbols` (fixed with disjoint tag ranges + a
  guard): add a sabotage row for it). **Stage 3 DONE a55f2bf** (one lane per rune, lane k at 2+92k/15 %, permutation
  chosen by colour alternation then least move; 1e hard check: 0 ink-on-ink, 0 failing frames on both pages; worst
  textDim 4.534 = a lone flaring rune; iOS 851/0, JVM 1030/0). **Stage 4 (T7 edge glow + gap audit) dispatched.**
  Then stage 5 look (pairs, still frames, gfxinfo incl. You's second clock, power saving), Opus review, sabotage
  (plan's 30 rows + the crash row + a lanes row), gate, records.
- **D-243 stage 4 DONE dcdb398** (edge glow both shells; gap audit: iOS Add a person labels 6 pt from cards, so EDGE_SIGMA
  3.5 -> 3.0, reach/clear distance 6.00 pt, decision to record; the plan's grown-chamfer formula was wrong: c + d(2 -
  sqrt2) is right; Android LedgerCard clipped the glow away: fixed; iOS 854/0, JVM 1033/0). **Stage 5 dispatched:** fix
  the iOS sign-in hero glow (Form row clips it; Android shows it: twin defect) + the full look (pairs, still frames,
  gfxinfo incl. You's second FrameGate loop, power saving, twins). Then Opus whole-branch review, sabotage (plan rows +
  the Canvas-symbols crash row + lanes row + grown-chamfer row + LedgerCard clip row), gate, records, merge.
- **D-243 stage 5 DONE e2e783e** (iOS sign-in hero as Section header; look report `.drafts/d243/look/report.md`: one
  30 fps grid on You, budget met, power saving 0 frames). **Opus whole-branch review: Approved with minors; fix wave
  65019b0** (gap-rule guards both shells + CLAUDE.md line; ᚠ origin pixel-snapped, rim still 6/255: accepted, decision
  149; sign-in hero not a heading; motion.py prints every pin; notch test tightened; Android hero cache keyed on
  colours; unused ProfileCard deleted; iOS 856/0, JVM 1034/0). **Sabotage matrix dispatched** (two worktree lanes,
  ~35-40 rows, results `.drafts/d243/sab/`). Decisions 144-150 drafted `.drafts/d243/record/decisions.md`. Then gate,
  records (+ ADR-0041 Motion amendment), merge, cleanup (poster caches).
- **D-243 sabotage: 45 rows (30 EXACT, 7 LISTED, 4 BROADER, 4 SURVIVED); the 4 survivors pinned and re-run EXACT
  (`.drafts/d243/sab2/`). Gate 23/23 (21:05Z). RECORDED (BUILD-LOG D-243, decisions 144-150, ADR-0041 amended).
  Merging.**
- **Then U25 settings sync (D-244):** design `.drafts/u25/design.md`; the founder's privacy line chosen (plain list,
  below); my calls decided (section 13 of the design: first-updated phone fills the account; account wins at first
  sign-in; Less motion syncs explicit values; no per-phone opt-out; no Appearance line). New ADR-0043. Hosting redeploy
  of the privacy page only with his yes on 0.5.1's day.
- **Owed to the founder at the end:** the before/after pairs of D-242 (`.drafts/d242/look/pairs/`) and D-243; the
  Developer Tools exemption reminder before any signed build; the 0.5.1 What to Test notes at release prep.

**The founder's answers (pop-ups, 2026-10-20, session 10 start):**
- **0.5.0 is out** (iOS 0.5.0 Build 7 on TestFlight, Android 0.5.0 / 5000 UPLOADED to Play). No 0.5.0 rebuild: this
  session is all for the next update. **D-239 ships in 0.5.1.**
- **0.5.1's versions: both phones read 0.5.1: iOS 0.5.1 Build 8, Android 0.5.1 / 5001.** The 0.5.1 plan's release IS
  0.5.1 (no 0.5.2 shift, since there is no rebuild).
- **Build U27, U26, U24, U25 this session, in that order**, each a full unit (gate, look, records, merge); nothing deploys.
- **U26:** nothing ticked; the phone's currency first, labelled "Suggested"; Continue enables once a currency is tapped.
- **U27:** the done moment says "Group deleted"; back to Groups at once.
- **U25:** the icon follows a synced theme on the other phone, applied only when SKIPTI comes to the front (never in
  the background; iOS's own alert then, Android may need the home-screen icon placed again).
- **U24 Balance runes:** rising runes on the Norse Balance HERO (like the page), AND the rune that sits in the balance
  card; all of them livelier and glowing intermittently.
- **U24 also (the founder, mid-session 10):** "the Norse theme looks nice and everyone likes it, it's just less vibrant":
  make Norse's colours more vibrant, for its royal nature. Plan: a mock-up (today vs vibrant) as a private Artifact and
  his approval by pop-up before building; values from the palette script, every pair still measured (AppearanceTests).

**D-239 progress (session 10):** runner fixed (a compile red = a `.swift:L:C: error:` line; a control row I1c proves it);
I1 and I3 redone as `isLowPowerModeEnabled` reads, both EXACT; iOS guard widened. LOOK DONE on throwaway AVD d239-look
(4 GB): battery saver needs `dumpsys battery unplug` on an emulator (AC power ignores it); saver ON: idle 0 frames (art
paused), App icon row 51 frames in 1.5 s (moment plays); control animator 0: 5 frames (snap); saver OFF: 91 idle frames.
Shots `.drafts/d239/shots/look-saver-*.png`. OPUS REVIEW: Needs fixes, I1 Important (the done check's Norse ring turned
forever under power saving; Android's check canvas looped forever in every theme); FIXED: iOS `MotionClock.artTicks` +
`DoneCheck.moving(artTick:)` + `CheckMotion.landedElapsed` (a landed pause holds its pose); Android `DoneCheck.runFor`,
`LaunchedEffect(own, art.tick)`, the check draws from `p.elapsed` (no artCanvas in DoneMoments.kt); M1/M2 guards widened
(iOS any `.lowPower` = 4 in ThemeMotion.swift; Android lowPower count 16 + `isPowerSaveMode`/broadcast = 2), unused
`rememberShouldTick` deleted, M4 stale comments fixed on both shells, M5 ADR + decision text aligned. Classes green: iOS
5 classes TEST SUCCEEDED, Android 288/0. Sabotage round 2 (14 rows incl. I5 I6 A6 A7) running. AVD d239-look still
exists (delete at close-out). A mock-up agent is making the Norse vibrancy page (`.drafts/u24/norse-vibrant.html`);
publish it as a private Artifact, send him the link, approval pop-up.

**U24 Norse look (the founder's pop-up, session 10): VIBRANT** (mock-up https://claude.ai/artifact/KMKWvN6nQh6cHQ6p33n6yf,
files `.drafts/u24/norse-vibrant.html`, values `.drafts/u24/norse-vibrant-values.md`, generator `measure.py`/`build.py`):
gold #CDA750 -> #DFB043, midnight ground #07080E, jade owing #7FD4C1, copper owed #EE8A58, card-edge gold glow 0.18,
runes flare to 19% (motion.py needs a flare cap: the text floors cap the rune core at 19.5%), hero runes 0.20 peak.
**The Norse APP ICON KEEPS today's gold** (his answer). U25's design brief: `.drafts/u25/design.md` (setting records
inside record type 3, no rules or header change; founder calls in its section 13).

**D-239 gate 1 (2026-10-20 15:5x Z): 22 passed, 1 failed** (cargo deny: RUSTSEC-2026-0374, basic-toml unmaintained,
published that day; build-time proc-macro of uniffi_bindgen; decision 140: one named exception in deny.toml, control
proven both ways). Sabotage round 2: 13 rows red (11 EXACT, A2 A5 LISTED), I1c reads COMPILE-ERROR. AVD d239-look
DELETED. Next: gate 2, records, commit, merge, push, cleanup; then U27 (`.drafts/u27/apply.py` drafted) and U26
(`.drafts/u26/apply.py` drafted). The founder's reference screenshot of Norse Balance: `.drafts/u24/reference/`.

**D-239 gate 2: GATE PASSED: 23 passed, 0 skipped (2026-10-20T16:00:42Z, 3m35s; the exemption reaches Claude Code
2.1.299).** iOS 840 executed 0 failures; JVM 1018 carried forward from gate 1 (only deny.toml changed between);
cargo 400. RECORDED in BUILD-LOG (row, section 3, residue, changelog); decisions 137 to 140 in DECISIONS-TAKEN;
CLAUDE.md 10 gains the moment rule. Next: commit, merge --no-ff, push, cleanup.

**D-239 MERGED 8904cec (pushed, no Actions run, machine cleaned).** **D-240 (U27, branch d240-group-deleted):** built,
looked at on both shells (found and fixed an older iOS bug: an empty Group settings left after a local delete; the tab
now returns to its root), Sonnet review Approved with minors (M1 M4 M5 taken), sabotage 6/6 exact, gate 23/23
(16:30Z), recorded (decision 141). Next: commit, merge, push; then U26 as D-241 (`.drafts/u26/apply.py`), U24 as D-242
(the Vibrant palette) and D-243 (the livelier art), U25 as D-244.

**D-240 MERGED a50132b. D-241 (U26, branch d241-currency-suggested):** built from `.drafts/u26/apply.py`, looked at
on both shells, sabotage 4/4 exact, gate 1 21/23 (pinned counts: section labels, the You call site; updated), gate 2
23/23, recorded (decision 142). Next: commit, merge; then D-242 the Vibrant palette, D-243 the livelier art, D-244 U25.

**D-241 MERGED de09592.** **D-242 (U24 colours, branch d242-norse-vibrant): an Opus implementer is building it**
(brief: Vibrant values via the scripts, both shells, Aurora/Vardr unchanged, before/after pairs in `.drafts/d242/look/`,
report `.drafts/d242/report.md`; it commits on the branch, the controller reviews, sabotages, gates, records, merges).
**U25 privacy line (the founder's pop-up): the plain list** "Your app settings: theme, light or dark, text size, Less
motion, haptics, app icon and default currency, so your other phones match." under "With an account" on the privacy
page; live only with 0.5.1's Hosting deploy, on his yes that day (CLOUD-SECURITY row then). U25's other calls in
`.drafts/u25/design.md` section 13 are mine (decided as recommended there: first-updated phone fills the account; the
account wins at first sign-in; Less motion syncs explicit values both ways; no per-phone opt-out; no Appearance line).

**D-242 status:** implementer commit 89902a3 (Vibrant colours both shells, palette goldens pin all five columns, 4
sabotage rows red, iOS 843/0, JVM 1021/0, pairs `.drafts/d242/look/pairs/`); gate 23/23 on 89902a3 (`.drafts/d242/
gate.log`); Opus review Approved with minors; DECIDED (decision 143, to record): textFaint/neutral = the founder's
approved #9A8F76 as an approved input in palette.py (the derivation gave #90866E), and motion.py's Norse page check
keeps textFaint (the implementer had narrowed it); fix wave sent to the implementer (also M3 comment). After the fix
wave: re-gate, sabotage reuse, records (residue: Android Add expense "0" hint is textFaint at 0.45 on the page ground,
never floored, a pre-existing twin difference with iOS's system placeholder; no test enforces "no floored textFaint on
the Norse page ground", D-226 residue), merge.
**D-243 plan saved: `.drafts/d243/plan.md`** (30 sabotage rows, T0..T12; hero runes in lanes left of the ᚠ; halos as
cached discs; edge glow as 0.5 pt bands with a gap audit; ring via ArtTurn / Modifier.artTurn, upright each start).

**D-242 gate 2 23/23 on 1294013 (fix wave: textFaint #9A8F76, motion.py check restored), RECORDED (decision 143);
merging.** Next: D-243 from `.drafts/d243/plan.md` (implementer), then U25 (D-244).

**Order now:** D-239 close-out (I1/I3 redo + runner compile check, battery-saver look on a throwaway AVD, Opus review,
full gate, records, merge, push, cleanup); runbook note for 0.5.1's versions; the exemption reminder; then U27, U26,
U24, U25 (U25 with a new ADR reversing ADR-0041's "never synced").

## (previous) NOW: 2026-10-20 (session 9, Fable, context full): 0.5.0 CLOUD DEPLOYED; D-239 (motion under power saving) MID-UNIT

**RESUME (the next session; the founder's opener is `.drafts/stop/fable-opener-3.md`):**
- **Shipped this session, all merged into `main` and pushed, no Actions run:** D-237 the Fable pre-release audit and
  its three fixes (decisions 131 to 134; main 5b5aec7); the read-only production checks and the wider sweep (clean;
  CLOUD-SECURITY section 7); decision 135 (no 0.4.0 set-ups); the runbook `docs/RELEASE-0.5.0.md` now version 4;
  D-238 the release lint tool's checksums (decision 136; f24a658).
- **The founder deployed the 0.5.0 cloud himself on 2026-10-19 evening, from f6ce22f:** E1 indexes (READY), E3 the 28
  Functions (512 MiB, 1 CPU, concurrency 6, 10 instances), E5 the Firestore rules (live = repo byte for byte), E6
  Hosting (`scripts/cloud-audit.sh` CLOUD AUDIT PASSED, every check). CLOUD-SECURITY section 2 has a row for each.
  **The cloud side of 0.5.0 is complete.**
- **Phase F (apps) is the founder's and in progress:** iPhone 0.5.0 (7) uploaded to TestFlight (he is testing it);
  Android `bundlePlayRelease` passed after D-238 (whether 5000 was uploaded to Play: ASK HIM; if it was, the next
  Android build must be 0.5.1 / 5001, CLAUDE.md 10.3; iOS takes Build 8).
- **D-239 (branch `d239-moments-ignore-power`, commit 0fbcac6 on top of main f24a658): decision 137, the founder's by
  pop-up: power saving stills the ART ONLY; every moment (done check, settle celebration and its burst, switch travel,
  row opening) plays unless Reduce Motion or Less motion.** Why: on his iPhone 17 (iOS 27, Adaptive Power ON) iOS
  reported Low Power at launch with the switch off, and D-214's rule froze every animation (the App icon row snapped;
  Low Power on/off fixed it; force-quit did not). His LG G8X (Android 12, 60 Hz, 5.6 GB, animator scale 0.5) ticks at
  30 fps now; its early stuck spell cleared on a resume (same family, unproven). Evidence `.drafts/d239/`.
  Built: iOS `MotionClock.momentTicks` + ArtCanvas `moment` flag (the burst); Android `MotionState.moment()`;
  `Celebration.showsBurst(reduceMotion:)` on both; a source guard per shell (only the art reads power saving); ADR-0041
  amended; decision text drafted `.drafts/d239/decision.md` (NOT yet in DECISIONS-TAKEN). Tests: iOS five classes 255/0,
  Android five classes 287/0 (before the extra guard lines).
  **Added after 0fbcac6:** extra guard lines in `ios/SkiptiTests/ThemeMotionTests.swift` (the canvas's moment
  branch) and `android/.../ThemeMotionTest.kt` (DoneMoments' two `.moment()` reads, the overlay, the burst canvas), and
  the 0.5.1 plan's U24 to U27. **Sabotage** (`.drafts/d239/sabotage.py`, results `sabotage-run.log` / json): I2, I4
  EXACT; **I1 and I3 were COMPILE ERRORS** (PowerState.shared.lowPower is main-actor isolated; the runner misread them
  as green): redo them as e.g. `momentTicks` returning `false` under `ProcessInfo.processInfo.isLowPowerModeEnabled`,
  and widen the iOS guard to count `isLowPowerModeEnabled` too; fix the runner's compile check (`error:` lines mean a
  compile red whatever else the log says). Android rows all red as designed: A1, A3, A4 EXACT; A2 LISTED
  (every_compose_animation_passes_the_door + only_the_art_reads_power_saving); A5 LISTED (SettledTest celebration).
  Every file restored (tree clean but the committed guard lines). The guard lines and the plan are COMMITTED on the
  branch (see git log). **Then:** the emulator look (`.drafts/d239/look.sh`: create a throwaway AVD
  d239-look with hw.ramSize 4096, boot, install the debug APK, launch with no account, `saver 1`, `idle` (expect about 0
  frames: the art pauses), navigate You > Appearance and `tapcount 'App icon'` (expect many frames: the row animates),
  `saver 0`, kill, delete the AVD); an Opus review; the full gate; records (DECISIONS 137 from the draft; BUILD-LOG
  D-239 row, section 3, residue, changelog; WORKLOG); merge --no-ff; push; cleanup.
- **Then ask the founder** whether D-239 ships as a 0.5.0 rebuild (iOS Build 8; Android 5001 as 0.5.1 if 5000 is on
  Play) or waits for 0.5.1, and remind him: the Developer Tools exemption OFF before any signed build.
- **0.5.1 plan additions (the founder, 2026-10-20; `docs/superpowers/plans/2026-10-19-0.5.1-plan.md`):** U24 livelier
  art (Norse runes glow as they rise, runes on Balance "as we talked before", the Futhark ring round You's picture
  turns slowly), U25 settings sync across phones (theme, mode, text size, switches, icon, default currency; reverses
  ADR-0041: a new ADR), U26 the first-run default currency asks instead of pre-ticking, U27 deleting a group shows a
  done moment at once.
- Machine at the limit: clean. Emulators stopped, every simulator shut down, both poster caches deleted, the repro's
  Release build deleted, the app uninstalled from d214-look (its Face ID enrolment kept). The founder's LG may still be
  plugged in over adb (read-only use only). Branch d239-moments-ignore-power pushed (0b7bacc); main is f24a658.

## (previous) NOW: 2026-10-19 (session 9, Fable, close): THE AUDIT IS DONE, D-237 MERGED. NEXT: THE READ-ONLY PRODUCTION CHECKS (HIS YES), THEN THE DEPLOY

**RESUME (any session; the founder's calls are listed under "Open for you" in DECISIONS-TAKEN, item 13 is new):**
- **D-237 (the Fable pre-release security audit for 0.5.0 and its three fixes): MERGED --no-ff into main, pushed, no
  Actions run.** Gate 23 of 23, 0 skipped on 137d692 (9m05s; cargo 400, emulator 270, JVM 1,015, hosted suite in the
  gate log). Report `.drafts/audit-050/findings.md`; sabotage 17 rows (15 EXACT, S5 BROADER, F2 LISTED, every row on
  137d692; `.drafts/audit-050/sabotage-report.md`); Opus review Needs fixes then Approved with minors
  (`.superpowers/sdd/d237/review.md`). Records: BUILD-LOG D-237 (row, section 3, residue (a) to (h), changelog),
  DECISIONS-TAKEN 131 to 134 (+ "Open for you" item 13), CLOUD-SECURITY (G-29 resolved; G-08 and G-31 D-237
  paragraphs; the leads (a) to (j) answered; the creator-regain correction; 2's pending paragraph; 4.2; 4.3 the Auth
  record), RELEASE-0.5.0 version 2 (E3 table, E4 read-back, E5 0.4.0 check, D3 no Storage bucket, G1 second account).
- **What the audit fixed:** A1 a claimed place merged into "you" in every group (a co-member could make another
  person's history yours on your phone with one invite): per group now, read side (core) and write side (both
  shells' Placing); A2 the next record ids were predictable after a receive (a co-member could pre-empt your edits
  for a day through `seen`): a random 20-bit step per stamp, carries 5 min past the bound before a lowering; A3 one
  copied profile stripped a person of D-230's marks: the earliest registration owns an id, a full 20-holder read
  fails closed (Functions; no index, no rules change; deploys with D-235's set).
- **What it recorded (0.5.1 or the founder):** core M2 backdated Create (Low); the server's `createdAt` in the vault;
  the shells' cosmetic "is me" sets; Firebase D1 D2 D3 D5; the Auth record's name and picture (disclosed in 4.3);
  G-22; the G-04 family and capacity for internal testing; I3 and M6 (a second phone's "you" id and a re-registered
  account's id can be registered by a copy first: fails closed as before).
- **Next, in the founder's order:** (2) the read-only production checks, each only with his yes in chat
  (`.drafts/audit-050/prod-reads.py`: Firestore REST reads with the gcloud login, writes nothing; plus
  `gcloud storage buckets list`, `gcloud functions list`, the IAM policy for `skipti-functions`; the Auth record is his
  own console look); (3) the runbook review is in `.drafts/audit-050/findings.md` ("The runbook ... what I would
  change") and applied as version 2; (4) the Developer Tools exemption OFF before any signed or store build (CLAUDE.md
  13; runbook D1). Then his device checks and the deploy with his yes per step (indexes READY, Functions, rules, apps).
- **(2) DONE with his yes (2026-10-19): the read-only production checks** (CLOUD-SECURITY section 7, the paragraph
  after the leads' answers): all clean; 17 Functions at 256 MiB / 80 / 10 as `skipti-functions`; no user Storage
  bucket; no composite index yet; IAM exactly the five roles. Left: his own look at one Auth user (4.3).
- **The wider sweep with his CLI access (same evening, read only):** live rules = 8252c7f by ruleset; Google and Apple
  only, MFA off; Auth records' fields read (two of four have no email: the iPhone's openid-only flow); invokers right;
  scheduler ran 21:29Z; APIs 50 (`analyticshub` new since D-201: Google's, for him to disable); cloud-audit.sh 30 ok,
  13 expected pre-deploy fails; the pinned CLI supports 512 MiB + concurrency 6 (gen-2 default one CPU). Recorded in
  CLOUD-SECURITY section 7 and 4.3. **Decision 135 (his): no 0.4.0 set-ups; testers update on the day.** The runbook
  is version 3 (Phases A and B done, C dropped, D2/D3/E3/G2 reworded).
- **THE DEPLOY HAS STARTED (the founder ran it, 2026-10-19 evening, from f6ce22f):** E1 the two indexes (READY), E3 the
  Functions (28 current, 512 MiB / 1 CPU / 6 / 10, purge 1800 s; cloud-audit.sh all ok but `privacy/index.html`,
  which E6 fixes). Logs since: only scanners' GET probes, all refused. CLOUD-SECURITY section 2 rows written. **E5 the
  Firestore rules released 02:37Z UTC; the live ruleset equals the repo's byte for byte; row written. Next: E6 Hosting
  (the founder runs it), then cloud-audit.sh, then the apps (F).** **E6 done: Hosting live, CLOUD AUDIT PASSED (every
  check). THE 0.5.0 CLOUD DEPLOY IS COMPLETE.** The runbook's Phase F rewritten (iPhone F1 to F5 with a Terminal archive
  and upload, Android F6 to F9, every command a paste block, the store notes inline), Phases H and I as blocks. Next:
  the founder's Phase F, then G (device checks), then H (tell testers).
- **D-238 (2026-10-19 night): `bundlePlayRelease` failed at `lintVitalAnalyzePlayRelease`** (the release lint tool had no
  checksums: the gate never builds a release). 24 files added offline, cross-checked against the repos' published SHA-1s,
  proven (release lint passes; a wrong checksum fails; android-app and android-test pass). Decision 136, CLAUDE.md 7.
  Merged; the founder reruns F7.
- **D-239 investigation (2026-10-19 late): the founder's 0.5.0 builds show NO animation on either phone** (iPhone
  TestFlight and Android Play; all three themes; Less motion off; no Low Power or Reduce Motion). The motion engine
  (D-214 on) never ran on a real phone or in a release build before (0.4.0 predates it; every look used Debug on the
  simulator or emulator). Code read: the door (stored ?? lowRam, the phone's setting, low power, active) agrees with
  the row on both shells. Next: reproduce Release vs Debug on d214-look (`.drafts/d239/`, two screenshots compared).
  Also asked: themes do not sync between phones = by design (ADR-0041 "chosen per phone, never synced"); a sync is a
  0.5.1 product call, his. A macOS session quit at 23:44 killed the first Release build (no reboot; nothing lost).
  **FOUND (2026-10-20 00:15):** on the founder's iPhone 17 (iOS 27; Reduce Motion, Low Power, Less motion all off) the
  App icon row SNAPS (so `motionReduced || PowerState.lowPower` is true); Less motion on/off changed nothing; Low Power
  Mode ON then OFF made everything move. So iOS reported `isLowPowerModeEnabled` true at launch (force-quit did not
  help) while Settings showed Low Power off: very likely the iPhone 17's Adaptive Power (unproven). D-214's rule (still
  under Reduce Motion or Low Power) then stills the art AND every foreground animation. Android (LG G8X, Android 12,
  60 Hz, 5.6 GB, animator scale 0.5, battery saver 0): ticks at 30 fps now through home/back and sleep/wake; its early
  stuck spell cleared on a resume (when power state is re-read): the same family, unproven. Measured: release and
  debug both animate on d214-look (0 px still vs ~180k px moving in 3 s); the signed-in debug build moves; the app
  lock is not the cause. Also seen: every LG frame is "janky" at 32 ms (the Aurora blur on that phone; decision 79's
  overturn condition for G10). The fix is the founder's product call (pop-up): what Low Power stills.
- Machine at close: no worktrees, no throwaway devices, no emulators; the gate simulator shut down and both poster
  caches deleted.

## (previous) NOW: 2026-10-19 (session 8, Opus, close): EVERYTHING FROM OPUS IS DONE. NEXT: THE FABLE SECURITY AUDIT

**RESUME (session 9, Fable; the founder's opener is `.drafts/stop/fable-opener-2.md`):**
- Merged this session, nothing deployed: D-230 held places (f470a63), D-231 Less motion (f7b58ba), D-232 Aurora dark by
  default (9db6caa), D-233 the decision 31 bake measured and not shipped, D-234 the twin-fix unit (c0b61d7), D-235 the
  Opus pre-release audit and its fixes (78be530: 1 Critical, a group money edit never reached other phones since D-195,
  fixed and proven on devices; 9 Highs fixed), D-236 release prep (0.5.0: iOS build 7, Android 5000; the runbook
  `docs/RELEASE-0.5.0.md`; the 0.5.1 plan `docs/superpowers/plans/2026-10-19-0.5.1-plan.md`; the vault key's copies
  zeroed, decision 129; the privacy page's picture clause, decision 130). Decisions 53 to 130 in DECISIONS-TAKEN.
- The founder's calls this session (pop-ups): 119 CSV raw, 120 lock-screen pushes keep note and amount (SPEC 3.8), 121
  invite names from the server, 122 the quiet "could not sync" line, 123 then 129 zeroize now, 124 CLOUD-SECURITY never
  public, 125 copy 83 and 84 kept, 126 deploy server first, 130 the privacy clause; earlier: the Less motion copy, the
  Shake Shack sample line, the App icon expanding row.
- **Next (the founder's order, docs/RELEASE-0.5.0.md):** Phase A the Fable security audit (CLOUD-SECURITY section 7:
  G-08, G-31, every open G-nn, the D-235 leads (a) to (j), the owed correction near line 206, the D-235 "for Fable"
  list, the read-only production checks with his yes); then his device checks, the Developer Tools exemption OFF before
  any signed or store build (remind him), the deploy with his yes per step (indexes READY first, Functions, rules,
  apps), testers update the same day; the store notes wait for his choice of variant (`.drafts/release-050/notes.md`).
- Machine at close: no worktrees, no throwaway devices, no emulators, poster caches deleted.

## (previous) NOW: 2026-10-19 (session 8, Opus): D-230 MERGED (f470a63), D-231 MERGED (f7b58ba). D-232 TO D-235 MERGED (D-233 no ship). NEXT: D-236 RELEASE PREP, THEN THE FABLE OPENER

- **The founder's calls (pop-ups, 2026-10-19), decisions 119 to 126:** CSV raw (119); lock-screen pushes keep note and
  amount, SPEC 3 amended (120); invite push names inviter and group from the server (121, built in D-235); a refused
  record shows "One change could not sync. It is still on this phone." on its group (122, built in D-235); zeroize in
  0.5.1 (123); CLOUD-SECURITY never public (124); decisions 83, 84 kept (125); deploy server first: indexes (READY),
  Functions, rules, apps, testers update the same day (126).
- **D-235 AUDIT DONE, FIXES UNDER WAY (branch d235-audit-fixes from 318c891):** 1 Critical (core C1: a group expense's
  money edit never reaches other phones since D-195; both shells refuse it as wrong-group), Highs: no money validation at
  ingest (core H1), unbounded HLC receive (core H2 = iOS H3 = Android M4), a removed member's own invite rejoins them
  (Firebase H1), the iOS reinstall is silently signed in (cross H1 = iOS H2, guarantee 5), the iOS app lock is an overlay
  only (iOS H1), Android double save (H1) and a cloud picture that crashes every member (H2). Triage with decisions 103
  to 109 and the founder's calls: `.superpowers/sdd/d235/triage.md`. Waves A (core) and B (Firebase) running in
  parallel; then C (iOS) and D (Android) in parallel, then E (cross), reviews, a look, the gate.
  Progress: core A 49703fb + rounds 2-3 (causal last writer, decision 104 amended; stamps clamped, text caps 110);
  Firebase B d4a4d01 + rounds 2-4 (a member can no longer make themselves unremovable: per-author reads, removals
  counter for placeholders only, decision 113; two composite indexes: deploy them first and wait READY); iOS C 85ef09b
  + rounds 2-4 (lock curtain window, reinstall sign-out + owner gate, ingest checks, refused uploads parked); Android D
  15c9edc + rounds 2-4; cross E 9030e1c (email out of docs), ae50657 (CI pinned), 01d89ee (no emoji font fetch without
  an account). Open: E1 Gradle verification (decision 115), iOS round 5 defers Firebase configure until an account
  (decision 116: no DNS lookup without an account), then the look, the whole-branch review, the gate. UPDATE: E1 done
  7d81334 (Gradle verification, decision 115); iOS round 5 a75889d 0979a88 (decisions 116, 117: no Firebase call without
  an account, measured); Android rounds 5-6 (b1cefe1, 5162e62). All waves done at 0979a88; running: the whole-branch
  review (`.superpowers/sdd/d235/final-review.md`) and the paired look (`.drafts/d235/look/`). DONE since: final review
  Approved with minors (+ confirmations 1, 2); final wave 5659ec7, 9b1f06d; the founder's calls 121 (b031579, ac859a1) and
  122 (b08c71b); decision 127 reverted by 128 (7ef0bf5, 763cf36); look fixes b0f3976, 00a7ed7; the look proved the
  Critical fixed end to end on devices; the re-look at 00a7ed7 PASS. Full gate on c6e68df `GATE PASSED: 23 passed, 0
  skipped` (5m29s; cargo 389, hosted 837, JVM 1014, rules 267); records applied (BUILD-LOG D-235 + incident 31,
  DECISIONS-TAKEN 103 to 128, CLAUDE.md seven lines, CLOUD-SECURITY pending paragraphs for Fable, SPEC 3.8 decision 120).
  MERGED (see the line below). Ledger lines
  "Wave ..." and decisions 103 to 116 in `.superpowers/sdd/progress.md`.
- **D-234 MERGED c0b61d7** (records 8b6b735; gate 23/23 on e359a9b). **D-235 the Opus pre-release audit:** five
  read-only Opus auditors from c0b61d7 (core, Firebase, iOS, Android, cross-cutting; common brief
  `.superpowers/sdd/d235/audit-common.md`; reports `.superpowers/sdd/d235/*-audit.md`); then a triage, fix waves, reviews,
  a look where UI changes, the full gate, records, merge. Then D-236 release prep (versions 0.5.0: iOS build 7, Android
  5000; `docs/RELEASE-0.5.0.md` from `.drafts/release-050/`; notes for the founder's approval; the 0.5.1 plan), then the
  Fable opener.
- **The founder (2026-10-19, now away):** after every item below is done, run a FULL OPUS AUDIT of everything (bugs and
  security) and fix what it finds (it comes BEFORE the Fable audit and does not replace it); then write the Fable opener
  ("Read CLAUDE.md first ...") as the very last step. While he is away: no pop-ups; every call decided on evidence and
  recorded in DECISIONS-TAKEN; nothing deployed, no production, nothing public.

- **D-232 (branch d232-dark-default from 24ea85f):** spec + plan f640c8d (decisions 73 to 77; the planner's four changes
  accepted: a settled mark so the migration runs once, iOS readers made optional, ADR-0036 amended too, the migration
  first at launch; decision 77: the launch frame follows the phone, Android's window theme becomes DayNight). Pre-D-232
  builds saved in `.drafts/d232/pre/` for the upgrade check. Briefs `.superpowers/sdd/d232/`.
  Built: T1 8095ba5, T3 a37ae9f, T2 20d5a75, fix 6606864 (the settled mark with commit()), T4 f9fa620 (ADR-0042); every
  task review Approved; whole-branch review Approved with minors; look PASS (F2: Android's selection toolbar followed the
  phone -> decision 78, 3ec870f: API 31+ setApplicationNightMode); final wave 1189b57 (M4), b550e65 (docs). Decision 78
  exposed a pre-existing Android app-lock bug (a configuration change locked the app at any timeout): fixed a0e7a34; the
  confirmation review found that fix FAILED OPEN on a re-creation before the first decision (Critical C1): fixed 69a5780;
  h1 (a stale in-front mark): 2e2adc8, a 2 s expiry. Device looks: decision 78 PASS, the lock on API 34 and 37 PASS, the
  no-lock rows at 2e2adc8 12/12. Full gate on 0ade7ca `GATE PASSED: 23 passed, 0 skipped` (4m02s; JVM 959 carried forward
  over identical inputs from 2e2adc8's run, CLAUDE.md 7); records applied (BUILD-LOG D-232 + incident 30, DECISIONS-TAKEN
  73 to 78, CLAUDE.md incident line). MERGED --no-ff at 9db6caa, pushed, no run; cleanup done (pre builds, poster
  caches).
- **D-233 decision 31 bake: MEASURED, NOT SHIPPED (decision 79):** p90 -0.17 ms against an 8 to 9 ms spread, +2.2 MB
  GPU memory, pixels identical; the D-226 +4.5 ms no longer reproduces (control without card art -1.3 ms). Recorded in
  BUILD-LOG (D-233 row, section 3, changelog) and DECISIONS-TAKEN 79; patch kept `.drafts/d233/bake.patch` for the
  real-phone check; branch deleted (no commits).
- **D-234 status (gate next):** whole-branch review Approved with minors (I1 the nine card paddings -> decision 102,
  c5bb6a5, Android follows iOS, CardPaddingParityTest; JVM 973/0); the paired look PASS on every item both phones
  (`.drafts/d234/look/report.md`; F1 iOS long-note ellipsis at 402 pt residue; F3 first-read username jump to 0.5.1);
  d214-look's stale device keys deleted. Full gate on e359a9b `GATE PASSED: 23 passed, 0 skipped` (4m06s; JVM 973 carried
  forward over identical inputs from c5bb6a5); records applied (BUILD-LOG D-234, DECISIONS-TAKEN 80 to 102, CLAUDE.md
  two lines: the width tables for new settings rows, Android portrait). MERGED (see the line below).
- **D-234 twin-fix unit (branch d234-twin-fix from b6de2d6; spec+plan bf96ee8; decisions 80 to 101):** T1 fc9b911,
  T3 099be14, T2 2fc67aa, T4 db4cdb8, T5 1716d93, T6 e17f85a; every task review Approved (T6's verdict in the
  whole-branch review); decision 100 Import summary 3 lines (copy kept), 101 the width test's weight bound 1.01. Running:
  the whole-branch review (`.superpowers/sdd/d234/final-review.md`) and the paired look (`.drafts/d234/look/`). Moved
  to 0.5.1: L2 Import/Review redesign, iOS re-tap large title, largest text step parity, Add expense as a sheet on
  Android. Copy changes for the founder to overturn if he likes: decision 83 Haptics "A tap when it is done", 84 You's
  account row "Google · 2 phones".
- **D-234 (original planning line):** list `.drafts/d234/list.md` (the founder's A1 sample line "Lunch at Shake Shack"
  $48.00 USD, A2 App icon as one expanding row; T1 a real width test; L1 to L14 the looks' findings); Opus planner
  (read-only) writing `.drafts/d234/spec.md` and `plan.md` (decisions 80 onward).
- Then: release prep (versions 0.5.0: iOS build 7, Android 5000; notes; device checks), 0.5.1 plan, the FULL OPUS AUDIT
  and its fixes, the Fable opener.

- **D-230 shipped:** merged --no-ff into main at f470a63 and pushed (no Actions run); gate 23/0 on 062650a; records in
  BUILD-LOG, CLOUD-SECURITY (paragraphs for the Fable audit), DECISIONS-TAKEN 53 to 67. Cleanup done (no worktrees, no
  tmp dirs, poster caches of AFAF4221 and d214-look deleted). Nothing deployed.
- **D-231 Less motion:** spec `.drafts/d231/spec.md` (decisions 68 to 72), plan `.drafts/d231/plan.md` (T1 pure, T2 iOS
  door + T3 Android door in parallel, T4 the row + Lucide `sparkles` v1.59.0 + NOTICE, T5 the paired look); both are
  committed on branch `d231-less-motion` as docs/superpowers/specs|plans/2026-10-19-d231-*.
- **D-231 progress:** T1 c45739f, T3 0355cc5 (parallel with T2), T2 f522a1d, T4 5312957; every task review
  Approved (minors m1 to m10 in the ledger); summary copy revised to "Holds the art still, fewer animations" (decision 69);
  hosted 780/0, JVM 945/0 at T4. Running: the paired look (Opus, `.drafts/d231/look/report.md`) and the whole-branch
  review (Opus, `.superpowers/sdd/d231/final-review.md`): Needs fixes (I1 Android tab bar not gated), fix wave
  c802a65 + copy 8a96b93 ("Still art, fewer animations", the founder's pop-up after he saw the old line truncate),
  confirmation Approved with minors. Look PASS behaviour on both phones; re-look at 8a96b93 PASS every capture.
  Full gate on 48e571b `GATE PASSED: 23 passed, 0 skipped` (7m21s; cargo 369, hosted 780, JVM 947); records applied
  (BUILD-LOG D-231, DECISIONS-TAKEN 68 to 72, CLAUDE.md NOTICE line + Less motion paragraph). MERGED --no-ff at
  f7b58ba, pushed, no Actions run; cleanup done (no worktrees, no tmp dirs, poster caches deleted). D-232's planner
  (Opus, read-only) is writing `.drafts/d232/plan.md`.
- **The founder's asks for the twin-fix unit (pop-ups, 2026-10-19):** the text-size sample line is fixed in US dollars
  for everyone, "Lunch at Shake Shack", $48.00 (was "Dinner at Thalassa", 2400 in the default currency); the App icon
  section becomes one row that expands inline (title, current choice, chevron; drawn by us, identical on both phones;
  the expand honours the motion door). Also from today's looks: the settle card's subtitle on the "Where the money went"
  label (D-221), Vardr still-frame sign positions, avatar colours, the "Marks this as paid here" wrap.
- **D-232 dark default:** spec drafted `.drafts/d232/spec.md` (decisions 73 to 76; a launch migration keeps existing
  installs on System; ADR-0042).
- Then: decision 31's bake + the twin-fix unit (D-228 O1 to O5, D-229 F1, F2, and the iOS removed-group avatar check),
  release-gate work (no audit), 0.5.1 plan. The session 8 progress lines below stay as the record of D-230's finish.

## (previous) NOW: 2026-10-18 (session 7, Fable, close): D-230 HELD PLACES BUILT AND APPROVED, NOT YET GATED OR MERGED

**Session 8 progress (Opus, 2026-10-19; the founder said yes to the opener by pop-up, Remote Control on):**
- Functions round 6 DONE at 55ec25a: the stamp's counter = the record's index in its call; emulator 228/228 (226 + 2);
  row S1 exact (3 counter-pinning tests red, 225 pass); `.drafts/d230/r6/report.md`.
- Look F1 ATTRIBUTED (`.superpowers/sdd/d230/f1-attribution.md`): pre-existing since D-210 (not D-229, not D-230;
  a core test passes the same at 29baa72 and b7296e3): the activity line reads the redistribution's raw `from`, which
  the account's own phone merged into "you" (`otherYouIds`), so `historyNames` has no name; an unnamed `to` is dropped
  too. Decision 67: fix it in D-230's branch before the gate (shells only: `historyNames` fills merged ids with the
  account's name and applied aliases' `from` with the `to`'s name, never overwriting). F1 fix round DONE at ab4adc2
  (`namingMergedPlaces` per shell; twin tests (a)-(e); iOS RolesTests 90/0, JVM RolesTest 104/0; 6 rows EXACT;
  `.superpowers/sdd/d230/f1-fix-report.md`). Its review (Opus, `f1-fix-review.md`) and a paired re-look of scenario 5
  (Opus, `f1-look-report.md`) running; then the full gate. D-231's decisions therefore start at 68.
- D-230 records second pass DONE (`.drafts/d230/record/records-report-2.md`; apply.py, apply_cs.py (adds paragraphs for the
  audit to verify, strikes nothing: G-items are Fable's), apply_decisions.py (53 to 67) all dry-run OK; waits for the
  gate log). F1 review (`f1-fix-review.md`): Approved with minors; M1 (a row for the group filter) and M2 (alias loop
  only when you is set): F1 round 2 DONE at a074837 (test (f), 12 rows EXACT, RolesTests 91/0, RolesTest 105/0,
  `f1-fix-report-2.md`; no re-look owed: M2 changes only the no-you path, which no signed-in look screen reaches).
- **D-230 GATED AND RECORDED:** full gate on 062650a `GATE PASSED: 23 passed, 0 skipped` (5m28s; cargo 369, hosted
  769, JVM 936, rules 228; `.drafts/d230/gate/gate.log`); BUILD-LOG row + section 3 + residue + changelog, CLOUD-SECURITY
  (D-230 paragraphs for the Fable audit to verify; nothing struck), DECISIONS-TAKEN 53 to 67, all through the apply
  scripts. NEXT: merge --no-ff into main, push, cleanup (poster caches), then D-231. F1 re-look PASS on both phones at ab4adc2
  (`f1-look-report.md`). For the twin-fix unit: iOS's removed group avatar looked faded, Android's normal (not like for
  like; check). D-232 spec drafted (`.drafts/d232/spec.md`, decisions 73 to 76, a launch migration keeps existing
  installs on System).
- D-231 plan written (`.drafts/d231/plan.md`, T1 pure, T2 iOS door, T3 Android door (T2 and T3 in parallel), T4 the row
  + sparkles + NOTICE, T5 look); planner's 4 deviations accepted (spec section 7); sparkles v1.59.0 verified.
- D-231 spec drafted (`.drafts/d231/spec.md`, decisions 68 to 72: Less motion = the app's own Reduce Motion, on
  Appearance after Haptics, on by itself on low-RAM phones until set, one door per shell held by source guards).

**RESUME (session 8, Opus; the founder's opener is `.drafts/stop/opus-opener.md`; ledger `.superpowers/sdd/progress.md`
"Session 7 run"; every report and review in `.superpowers/sdd/d230/`; amendments A1 to A10 in
`.superpowers/sdd/d230/amendments.md`, binding where they changed the plan):**
- **Rule (the founder, 2026-10-18): the pre-release security audit (CLOUD-SECURITY section 7, G-08, G-31, every open
  G-nn, the owed correction near line 206, the read-only production checks) is done by FABLE in a Fable session, not by
  Opus.** An Opus session leaves it listed and untouched. Everything else in the release-gate list is Opus's.
- **Rule (the founder, 2026-10-18): in the upcoming units Opus makes the decisions itself, on evidence, and records
  each as a numbered decision (DECISIONS-TAKEN, continue after 66) with What / Why / Risk if wrong / Overturn.** Only
  his own calls go to him by pop-up: hardware, money, product shape that changes a guarantee, anything public.
- **Step 1 of the opener DONE:** 31 CHANGED (record wrong: the AVD ran `-gpu host`; the cost is the per-card re-blur
  under a moving clip; chosen: option (d) bake the shared blur once, A/B with `.drafts/d226/fix2/ft.sh`; phone recipe in
  `.drafts/s7/decision-31-report.md`); 36, 37 CONFIRMED (new residue: evicting an intruder from the creator place costs
  the creator path for good; 0.5.1 `removedUid`); 44 to 52 CONFIRMED; the G-08 unit pulled before 0.5.0 = D-230.
  Findings: `.drafts/s7/redecide.md`. Recorded in DECISIONS-TAKEN (top) at ec97578.
- **D-230 held places (branch `d230-held-places`, tip cd4b2cc, 28 commits from b7296e3):** spec approved
  (`docs/superpowers/specs/2026-10-18-d230-held-places.md`), plan (`docs/superpowers/plans/2026-10-18-d230-held-places.md`),
  core (T1 + two fix rounds, Approved), Functions (T2 + five rounds, Approved), iOS (T3 + three rounds, Approved with
  minors), Android (T4 + four rounds, Approved), whole-branch security review Approved after A10
  (`.superpowers/sdd/d230/final-review.md`, "Confirmation"). Tests at the tip: core lib 357, emulator 226 (pure 19),
  JVM 930 (round 4 added none), hosted 763 expected (758 last full run; the gate's iOS leg proves it). Sabotage about
  150 rows across lanes (the records drafter counts them from the reports).
- **D-230 left to do, in this order:**
  1. **The look is DONE: 5 of 5 PASS on both phones** (`.superpowers/sdd/d230/look-report.md`, 19 pairs in
     `.drafts/d230/look/pairs/`; the join's mark lands inside acceptInvite with a server-random identity, the forged
     claim no longer locks the victim out, a fresh install's first join is marked, legacy data unchanged, the honest
     rejoin keeps Bo's history on Bo). **Look F1 to settle before the gate:** on the rejoiner's OWN phone the activity
     line reads "Unknown member's 550.00 moved to Al" where the owner's phones read "Bo's"; find whether it is this
     unit's (the seeded claim on the removed old place, merged into "you") or D-229's rejoin rule (build the pre-D-230
     tip or reason from `groups.rs`'s merge and the activity label's lookup); fix in a small round if it is ours, else
     a residue line. F3 (a forged claim naming this account's id costs one quota unit per push) is the known residue.
     The emulators for a look run as project `skipti-26` locally (as D-229's look did), not `demo-skipti`.
  2. **Functions round 6 (tiny, confirmation review F1):** in `heldDoc`'s server stamp, `counter` = the record's index
     in the call (ties within one millisecond break by list order), one test, one sabotage row, emulator suite green.
     Decision 54 gains "within a call, the list order breaks ties".
  3. **Full gate** on the tip (`./scripts/gate.sh --full`, background, `.drafts/d230/gate/gate.log`; nothing tracked
     edited meanwhile; expect `GATE PASSED: 23 passed, 0 skipped`; the iOS leg gives the hosted tally).
  4. **Records:** the drafter's second pass with `.drafts/d230/record/notes.md` (every section, the drafts are at
     `.drafts/d230/record/`: row.md, section3.md, residue.md, changelog.md, decisions.md (53 to 66), cloud-security.md
     + cs-old/new pairs + apply_cs.py, sabtable.py, apply.py; dry run passed; fill the placeholders from the gate, the
     look and the rows; the F5 row is MORE with its reason), then `python3 .drafts/d230/record/apply.py --gate-log
     .drafts/d230/gate/gate.log --apply`, `python3 .drafts/d230/record/apply_cs.py --apply`, DECISIONS-TAKEN 53 to 66
     appended from decisions.md, `python3 scripts/check-buildlog.py`, commit `[skip ci]`.
  5. **Merge** `git merge --no-ff d230-held-places` into main, push, `gh run list --branch main --limit 2` shows no new
     run; **cleanup** (`git worktree list` must show only the checkout; `.drafts/d230/**/dd*` and DerivedData gone;
     `/private/tmp/skipti-d230-*` gone; with the simulators shut down delete the poster caches of AFAF4221 and d214-look
     (CLAUDE.md 4)); WORKLOG shipped line.
  6. CLAUDE.md gets one line in section 7: a new group op needs an arm in every exhaustive switch over GroupOpIn
     (LedgerStore.swift scope(of:), LedgerStore.kt Placing.groupOf and groupOpRecord, both CloudSync refusals).
- **Then the opener's remaining items, in this order (Opus decides, records, builds with the usual loop):**
  - **Lower animation mode** (D-231): an in-app switch (Appearance page, both phones, twins) that turns the moving
    elements off, built on D-214's still frame (`MotionClock.shouldTick(reduceMotion:lowPower:active:)` on both shells;
    the still frames per art piece; the readers of reduceMotion: iOS DoneMoments, HomeShell, ListRows, OverviewScreen,
    SkiptiTabBar, Settled, ThemeMotion; Android AppRoot, DoneMoments, ListRows, OverviewTab, SkiptiBar, TabBar, Settled,
    ThemeMotion). Store it like the theme (UserDefaults / SharedPreferences in Appearance). Recommend in the record
    whether it turns on by itself on a slow phone (RAM class or measured frame time) with one line; note that decision 31's
    agent found the mode does not fix the Aurora scroll cost (that is the bake, below). Brainstorm, spec, plan, SDD.
  - **Aurora DARK default** (D-232): `AppearanceMode` default `.system` -> `.dark` on a new install on both phones; keep
    Light and System as choices; SPEC 11.1 ("light first") and ADR-0038 (light first stands) get a superseding ADR-0042
    (dark first by default, light kept as a requirement); check every contrast floor and the first-run screens in dark.
  - **Decision 31 (d) bake** (Android-only, small: Backdrop.kt, bake the shared blur into one quarter-size layer the cards
    sample; hoist the BlurEffect at Backdrop.kt:116; A/B with ft.sh; ship only if it measures and looks identical) and the
    **twin-fix unit** for D-228 O1 to O5 and D-229 F1, F2 (section 6 close block below).
  - **Release-gate work** except the security audit: version numbers (CLAUDE.md 10.3), paste-ready TestFlight and Play
    notes (no em or en dash), the device checks list, his look at the art and icon, the whole-repo dash sweep before the
    repo goes public, remind him to turn the Developer Tools exemption off before any signed or store build. The deploy
    order (rules, Functions (D-218, D-227, D-229, D-230: `sharePlaces`, the join's `records`, the `places` quota), then
    apps) and any production read only with his yes.
  - **0.5.1 planning only** (the list in the session 6 block below) plus: `removedUid` on removal records (decisions 36 x
    37), a revoke path for marks, the G-08 legacy places, the CHANGED/HELD copy pass (decisions 64, 66).
    **Added by the founder (2026-10-19), part of 0.5.1's onboarding:** the profile's name and picture are kept the
    moment they are typed or chosen, with no confirmation, so a person never sees that they were saved. Streamline it
    into an onboarding-style flow (a clear step with a confirm, e.g. "Your name" then "Your picture" then a done state),
    shared by first run and the profile page; twins; brainstorm against GUARANTEES 5 (no account nudge) first.
- **D-230 decisions for the record (53 to 66, full text in `.drafts/d230/record/decisions.md`):** 53 held places; 54 the
  server stamps a mark's identity (list order breaks ties within a call); 55 `places` quota 100; 56 a record failing a
  check never fails the join; 57 peekInvite answers the ids; 58 the server marks a removed place its caller held before
  (ever removed, no other account's invite ever); 59 seeded claims count from the member's add; 60 the mark wins over a
  raw member, the server refuses a raw-place you except the caller's own creator place; 61 the duplicate rule is per
  (group, member, you), the account's you; 62 candidates from the phone's own claim records, no place needed, capped at
  11; 63 a malformed join list refused, a failing record skipped, at least one for the invite's place; 64 a place once
  removed is never invited again; 65 a new install mints its you before the join; 66 the first account to mark an id
  keeps it, a join into another present member's mark is refused HELD.
- **Process lessons** (in `.superpowers/sdd/run-common.md`): Gradle rows need `--no-watch-fs`; every exhaustive
  GroupOpIn switch needs the new arm; a test stamp must exceed the real clock; rows never "in place"; one runner per
  set of rows.

## (previous) NOW: 2026-10-18 (session 7, Fable): RE-DECIDING 31, 36, 37, 44 to 52; then lower animation mode, Aurora dark default, release gate

- Session 7 opener: `.drafts/stop/fable-opener.md` (the founder's five items). Remote Control on.
- Step 1 research DONE, findings in `.drafts/s7/redecide.md` (31: `.drafts/s7/decision-31-report.md`). Outcome put to
  the founder by pop-up: 31 CHANGE (record wrong: the AVD ran `-gpu host`; the cost is the per-card re-blur under a
  moving clip; try option (d) bake the shared blur once; phone recipe); 36, 37 CONFIRM (36's alternative reopens the
  unremovable-member attack; new residue: evicting an intruder from the creator place costs the creator path for good,
  0.5.1 fix `removedUid` on removal records); 44 to 52 CONFIRM (G-08 stays the one hole; its fix is server-verified
  claim author and order carried into core; the lift of a 44 denial can cost a real placeholder member).
- Founder's answers (pop-up, session 7): 31 CHANGED (try option (d), the bake; A/B; real phone stays); 36, 37 CONFIRMED;
  44 to 52 CONFIRMED and **the G-08 unit is built before 0.5.0** (server-verified claim author and order into core).
  Recorded in `docs/DECISIONS-TAKEN.md` (top).
- Order from here (controller's call, security first): D-230 the G-08 unit; then the lower animation mode; then Aurora
  dark default; then the 31 (d) bake experiment (Android-only, small) inside the release-gate work with the twin-fix
  unit; then the release gate; then 0.5.1 planning only.
- D-230 held places: spec APPROVED by the founder (`docs/superpowers/specs/2026-10-18-d230-held-places.md`, approach
  "A plus the join-time mark"); plan written (`docs/superpowers/plans/2026-10-18-d230-held-places.md`, Tasks 1 to 5).
  Branch `d230-held-places` from main. Decisions 53 (design), 54 (10-minute skew bound), 55 (`places` quota 100),
  56 (a failed join-time mark never fails the join), 57 (peekInvite answers groupId and memberId) to record.
- SDD in flight on `d230-held-places` (ledger: `.superpowers/sdd/progress.md`, "Session 7 run"): T1 core 7bfbede; T2
  Functions d3f3b54 + 4fa3e53 (report pending); T3 iOS and T4 Android dispatched from 7bfbede. **T1 review found two
  holes and the controller a third; amendments in `.superpowers/sdd/d230/amendments.md`** (A1 the server marks a removed
  place the caller held before and the join carries `records` plural; A2 seeded claims ordered by the member's add
  identity; A3 the mark wins over a raw member added with its id, and the server refuses a raw-place `you`; decisions
  58 to 60). Core fixer running in worktree `/private/tmp/skipti-d230-core-fix` (branch `d230-core-fix`); T3/T4 told A1;
  T2 fix wave (A1 server, A3 server) after T2 reports.
- Later amendments (same file): A5 (the server's duplicate rule is per (group, member, you); the new place's record
  uses the account's "you"; the join's records capped at 11) after the T4 review; A6 (a removed creator's rejoin may
  mark its old raw place as `you`; `wanted` needs no place; rule 3 exclusion) after the core fix re-review, which found
  two honest rejoins ending with no place. Core fix 9783733 cherry-picked as 6199f54; round 2 running in the worktree.
  T2 fix wave, T4 fix wave, T3 running with A1 to A6. Reports and reviews in `.superpowers/sdd/d230/`.
- State at 21:00: Task 1 COMPLETE (7bfbede, fixes 6199f54 and b83ea52; three review rounds, Approved). Task 4 COMPLETE
  (fa907f7..5ae1dc1, Approved; minors queued for the final fix wave). Task 2 fix 7319a78 in, review running (Opus).
  Task 3 (iOS) still implementing with A1 to A7 (its sabotage worktree `/private/tmp/skipti-d230-t3-ios`). A7: the
  shells' candidates come from their OWN claim records (core voids a rejoin's claim). Decision 63: a malformed join
  list is refused (a phone bug), a record failing a check is skipped. Ledger has the detail.
- State at 21:50: Task 3 (iOS) in (6525410, 4eaf41a; hosted 758/0), reviewed Needs fixes (join wiring tests). Task 2
  rounds: 7319a78 (A1/A3/A5/A6 server), cd65abe (A8: removed place never invited, at least one join record), round 3
  running (A9: `everRemoved`; decision 64). Shells' final fix wave running on both lanes (`shells-final-fix.md`: join
  wiring tests, fresh-install first join mints the you, per-record heldRecords, triple filter, chunking, HeldRule shape,
  M4). Record notes for the drafter: `.drafts/d230/record/notes.md` (decisions 53 to 64, residues, process lessons).
- State at 22:05: Tasks 1, 2, 4 COMPLETE; Task 3's final wave in (585b9b4, cb87275), its closing re-review and four
  unfinished sabotage rows running; the WHOLE-BRANCH security review (Opus) running over b7296e3..e895286; the records
  drafter running from `.drafts/d230/record/notes.md`; the look brief written (`.superpowers/sdd/d230/look-brief.md`).
  Branch tip e895286 (23 commits). Decisions 53 to 64 drafted in notes.md.
- 22:40: the WHOLE-BRANCH review (Opus, `.superpowers/sdd/d230/final-review.md`): Needs fixes, two design holes:
  (I1) a copied id marked first became a takeover (D-229 had a denial): fix, decision 66, the first mark keeps the id and
  a join into another present member's mark is refused; (I2) one far-future record pushes every device's HLC forward
  and the skew check then blocks marks for good: fix, decision 54 reworded, the server stamps a mark's identity with
  its own clock. Amendment A10 (also M1: `claimed` skips members this account already holds). Functions round 5 and
  both shells' small rounds running; the iOS rows runner (F5, F9 to F11, R1 to R6) running. Records drafted
  (`.drafts/d230/record/`, dry run passes, placeholders for the gate, the look and the rows).
- NEXT: confirmation re-review of round 5 and the shells' rounds (the whole-branch reviewer), look, full gate, records
  (drafter second pass with notes.md's corrections, then apply.py), merge --no-ff, push, cleanup.

## (previous) NOW: 2026-10-18 (session 6, close): D-226, D-216, D-227, D-228, D-229 MERGED. STOPPED AT THE 0.5.0 RELEASE GATE. NEXT SESSION STARTS HERE

**RESUME (session 7; the founder's opener for Fable is `.drafts/stop/fable-opener.md`):**
- Merged and pushed this session, nothing deployed: D-226 d581626, D-216 4ea027e, D-227 87da0e7, D-228 08da315, D-229
  e646beb. Every unit: plan, SDD tasks, reviews, whole-branch review, paired look on throwaway devices, fix waves,
  re-reviews, full gate (23 legs since D-228), records through `.drafts/dNNN/record/apply.py`, merge --no-ff, no CI run,
  close-out cleanup.
- **The founder's decisions to review:** `docs/DECISIONS-TAKEN.md` (25 to 52, ranked; 31, 36, 37 first; 44 to 52 the
  D-229 chain). Decisions 19 to 24 he confirmed earlier.
- **The 0.5.0 release gate (what is ready, what needs him):**
  1. Security audit session: `docs/CLOUD-SECURITY.md` section 7 (G-08 the backdated claim, G-31 `shareAliases`, every
     open G-nn), re-verifying the live settings read-only. Owed correction there: around line 206 the creator-regain
     route is said to "close"; it stays open in the window before the creator's create reaches the cloud (D-229 m2).
  2. Read-only production checks, only with his yes: D-210's list; D-227's (no place with two current holders); D-229's
     count of records written under an older id before `shareAliases` deploys.
  3. Deploy order, each step only with his yes: Firestore and Storage rules, then the Functions (D-218, D-227, D-229
     with `shareAliases` and the `aliases` quota), then the apps. A dated CLOUD-SECURITY section 2 row the same day.
  4. Version numbers (CLAUDE.md 10.3) and paste-ready TestFlight and Play notes (public text: no em or en dash). What to
     Test lines owed: the icon follows your theme (Android may need the home icon placed again once); one account on
     two phones keeps its Owner pill; records you write in a group read under the name the group knows you by.
  5. Device checks on real phones: TalkBack and VoiceOver (drawn switch and choice rows, the placeholder spoken "none",
     the App icon rows), haptics, the celebration's start on iPhone, the Aurora blur's frame cost on a real Android
     (decision 31), the vault-recovery Try again, the icon switch on Samsung or Xiaomi launchers, the D-229 read-only
     banner and "You're already in this group.".
  6. His own look: `.drafts/d226/look/pairs/`, `.drafts/d226/relook/pairs/`, `.drafts/d216/look/pairs/`.
  7. A small twin-fix unit for what the looks found in passing: D-228 O1 to O5 and D-229 look F1, F2 (this block below).
  8. Before the repo goes public at the public beta: `python3 scripts/check-em-dashes.py --scope whole-repo` (2,705 hits
     today, almost all history docs) and sweep until it passes.
  9. Turn the Developer Tools exemption OFF before any signed or store build (CLAUDE.md 13): his action.
- **For Fable (his asks, also in the opener):** re-decide the decisions with safety first; a lower animation mode
  (in-app, aimed at lower-spec Android, on D-214's still frame); Aurora DARK as the default (SPEC 11.1 and ADR-0038 to
  update); then the release-gate work above; 0.5.1 planning only (the list below).
- Process lessons this session (in `.superpowers/sdd/run-common.md` and CLAUDE.md): the em dash rule is public-only
  (decision 42; guard `scripts/check-em-dashes.py`); UniFFI 0.32 hashes doc comments into its checksums (rebuild core
  after any core change); a core fix that must not disturb running shell lanes goes in its own worktree and is
  cherry-picked; background shell loops are killed before an agent replies; Gradle's reuse of a JVM test result over
  identical declared inputs is sound (D-227's open item closed).

## (previous) NOW: 2026-10-18 (session 6): finishing D-226, then D-216, D-227, D-228, D-229, then the 0.5.0 release gate

**FOUNDER ASKS FOR THE STOP AFTER D-229 (chat, 2026-10-18; do these at the 0.5.0 stop, do not lose them):**
1. Write `docs/DECISIONS-TAKEN.md`: every decision taken for him this run (31 to 43, plus 19 to 30 still standing),
   ranked Critical to Low, each with what, why, the risk, and how to overturn; 31, 36 and 37 called out at the top.
2. Write the next session's opener for **Fable** (claude-fable-5-1), in the usual "Read CLAUDE.md ..." form: research,
   then brainstorm and re-decide those decisions with safety and security first; propose a **lower animation mode** (an
   in-app toggle, aimed at lower-spec Android, built on D-214's still frame: most or all moving elements off, for speed);
   then the pre-0.5.0 work as usual (the CLOUD-SECURITY audit G-08, read-only production checks with his yes, deploy
   order, versions, store notes, device checks, the whole-repo dash sweep before the repo goes public).
3. **0.5.1 ideas (his, to plan, not to build now):** Norse "breathing" glow on active rune pages, and the selected
   group panel's outline glowing from one end round to meet at the other; voice: Siri (App Intents) and Gemini on
   Android to do things and ask about the app and its contents; an opt-in Advanced mode (terms accepted, nothing
   forced) for operator push messages and, later, in-app payments (UPI, Zelle). Joins the 0.5.1 list (Just me,
   onboarding, operator push, the survey prompt).
4. **For Fable, before the final release (founder, chat, 2026-10-18): make Aurora DARK the default** (a new install
   opens in Aurora with dark mode, on both phones). This changes a recorded decision: SPEC 11.1 and ADR-0038 say light
   first; Fable updates them (or writes the superseding ADR) in the same unit, keeps "follow the system" and light as
   choices, and checks every contrast floor and the first-run screens in dark.

- Session 6 started from the session 5 close block below (its RESUME list is the plan). Ledger:
  `.superpowers/sdd/progress.md` "Session 6 run" (newer than this block).
- D-226 fix wave 2 (Android, one Opus fixer) dispatched from ab40157: the 15 fps blurred art copy for frame time and
  F2's Darken cap at iOS's band colour. Brief `.superpowers/sdd/d226/fix2-list.md`, report `fix2-report.md`.
- Fix wave 2 done: 1e9f6b0 F2 (cap #4B6163 reads #53686A vs iOS #53696A, title 5.25:1 over every content); 950dfe0
  the 15 fps blur copy, REVERTED in 389332b (it bought nothing: 28 ms vs 26.5 before it, base 23.5; the cost is the
  cards drawing the blurred art in scroll frames, not the blur pass). Next: fix re-review + re-look (Opus, parallel).
- Fix re-review: Approved, 0 Critical, 0 Important, 5 Minor (`.superpowers/sdd/d226/fix-rereview.md`). Re-look
  (`relook-report.md`, 28 pairs in `.drafts/d226/relook/pairs/`): F1, F3, F4, watermark, D28 and the five-set sweep
  pass; F2 decided (32); R2 (one white iOS launch shot, not repeated) and R3 (Balance empty 2.2 s on a fresh AVD)
  to residue. Next: full gate at HEAD, then the records drafter, then merge.
- **D-226 MERGED d581626** (records ec9580d; gate 22/22 7m05s at 90343fd; 61 sabotage rows, all exact; cargo 325, iOS
  681, JVM 836). Pushed, no CI run. Close-out: no build products left; AFAF4221's poster cache deleted (328 MB),
  d214-look had none. Owed at the 0.5.0 release gate: Android Aurora frame time on a real phone (decision 31), the
  founder's look at the before/after pairs (`.drafts/d226/look/pairs/`, `.drafts/d226/relook/pairs/`).
- **D-216 the app icon: starting** on branch d216-app-icon. Plan `.drafts/d216/plan.md` plus session 6 amendments A2 to
  A5 (`.drafts/d216/amendments-session6.md`: brand sources stay local, T2 then T1 then T3 with T4, the tree moved).
- D-216 tasks done: T2 05638ac, T1 0aec3fd, T4 de54135, T3 3e61b8f (T1, T2, T4 reviews Approved; minors routed in
  `.superpowers/sdd/d216/minors.md`). iOS: one system alert on leaving Appearance, PrivacyCover behind it. Android: the
  process survives the alias switch (no contingency). Open: iOS `.onDisappear` does not fire on a tab switch (one rule
  for both phones to decide). Now: T3 review, whole-branch review and the paired look, in parallel.
- D-216 reviews: T3 Approved; whole-branch With fixes (1 Important: two Android aliases left on below API 33); look
  passes on both phones (one alert per leave, pid survives, twin measures within 0.25). Fix wave eb53c1a (Android
  8601f6c, iOS 7f0134b, CLAUDE.md). Records drafted in `.drafts/d216/record/`. Next: fix re-review, full gate,
  records, merge. 0.5.0 What to Test line: "the icon follows your theme (App icon on Appearance); on Android the
  home-screen icon may need placing again once after this update".
- **D-216 MERGED 4ea027e** (records b6ccc44; gate 22/22 4m52s at a19d89f; 45 sabotage rows, all exact; cargo 325, iOS
  693, JVM 857 carried forward). Pushed, no CI run. Close-out: no products left; poster caches deleted (AFAF4221 789 MB,
  d214-look 326 MB).
- **D-227 the two bugs: starting** on branch d227-group-bugs. Plan = `.drafts/d227/plan-v2.md` (adopted) plus an
  appendix of the v1 text it references and session 6 amendments, committed as
  `docs/superpowers/plans/2026-10-18-d227-group-bugs.md`.
- D-227 tasks done: T1 Functions ba741a3 + security fix 8cbc479 (decisions 36, 37; emulator 182 pass), T2 iOS bug (a)
  d2e66b4, T3 Android RoleAsking c42aa84, T5 Android places 82b0eab (JVM 875), T4 iOS places ba2517a (hosted 714). All
  task reviews Approved (T1 after its fix). Minors routed in `.superpowers/sdd/d227/minors.md`. Now: T4 review,
  whole-branch review and the paired look (local emulators), in parallel.
- D-227 after the tasks: whole-branch review With fixes (owner window); look PASS on both bugs, findings F1 to F4; fix
  wave 9e1b41f (Functions prose), d639654 (Android), 4db75c5 (iOS); re-review Needs fixes (a timer after sign-out; iOS
  lacked the live-page and reconnect-share twins); re-look PASS on decisions 38 and 39, R1 to R5; follow-up 1e9c6f4
  (Android), f705da5 (iOS). Records drafted in `.drafts/d227/record/`. Next: follow-up review, full gate, records, merge.
  Residue: re-look R3 (Android share waits for the listen stream, about 30 s), R4 (creator reads as a member until the
  first roles answer, as in 0.4.0).
- **D-227 MERGED 87da0e7** (records 106f794; gate 22/22 6m22s at 7cb11d3; 67 sabotage rows, 53 shell rows exact, the
  Functions rows' intended test red in all 14 (9 broader); cargo 325, iOS 725, JVM 886 re-run on the gated tree).
  Pushed, no CI run. Close-out: poster caches deleted (AFAF4221 789 MB, d214-look 482 MB), no products left.
  **Open item (before the release gate): the gate's Android test leg wrote no JVM XML at 7cb11d3 although f705da5
  changed Swift files the JVM parity tests read** (Gradle build cache on). The records drafter forced the suite on the
  gated tree (886, 0 failed), so nothing false was recorded, but the cause is unknown: investigate after D-229 (a
  cached test result over changed inputs is the D-162 class of hole).
- **D-228 the em dash sweep: starting** on branch d228-em-dash-sweep. Plan `.drafts/d228/plan.md` (A1 to A5) plus
  session 6 amendments; `inventory.py` re-run first.
- D-228 tasks done: T4 tools and docs 31e3fc2, T2 Android b46d68f + f845f31, T3 core and bindings af6eba3 (UniFFI 0.32
  hashes doc comments into checksums: rebuild core before any look), T1 iOS d4ab595 + 33beaee, T5 guard 1d33d19 (public
  scope per decision 42: 180 files, 0 dashes; `--scope whole-repo` today 2,704 hits in 46 files, almost all history
  docs; 23 gate legs; commit-msg hook attribution only). Task reviews T1 to T4 Approved. Now: T5 review, whole-branch
  review, the look. **Public-beta list:** run `scripts/check-em-dashes.py --scope whole-repo` and sweep (BUILD-LOG,
  WORKLOG, ADRs, plans, CLAUDE.md, AGENTS.md, 16 marked test escapes, 3 UniFFI template dashes in the bindings) before
  the repo goes public.
- D-228 look: all five items pass on both phones (17 pairs). **Outside D-228, for the release-gate list (twin and look
  defects found in passing):** O1 Android `--import-proof` crashes with WrongKey when the vault will not open (debug
  only); O2 Import and Review laid out differently on the two phones (no gap entry); O3 the "Fastest plan" lines in a
  different order on the two phones; O4 Android Add expense title clipped under the Cancel bar when scrolled; O5 vault
  recovery reason line and Norse title wrap differently. Evidence `.superpowers/sdd/d228/look-report.md`.
- **D-228 MERGED 08da315** (records 0636534; gate 23/23 6m47s at f44a721, the first gate with the colon line; 101
  sabotage rows, all exact; cargo 325, iOS 732, JVM 894 reused by Gradle over identical inputs; guard 184 public files,
  0 dashes; whole-repo 2,705 hits). Pushed, no CI run. Close-out: poster caches deleted (482 + 175 MB).
  **D-227's open item on the cached JVM leg is CLOSED:** every file the JVM tests read is a declared Gradle input, and
  Gradle reuses a test task's result (up to date or from its build cache) only when a passing run had byte-identical
  inputs; the agents' own runs had already covered the final tree. Only an undeclared input could fool it (CLAUDE.md 7).
  Correction to the public-beta line above: the 16 marked test escapes are exempt by design (they are not visible
  dashes); the whole-repo sweep is BUILD-LOG, WORKLOG, ADRs, plans, CLAUDE.md, AGENTS.md and the 3 UniFFI template dashes.
- **D-229 the write split: starting** on branch d229-write-split. Plan `.drafts/d229/plan.md` (A1: Phase B built, not
  deployed) plus session 6 amendments.
- D-229 built: core 92b7f50 plus four review-driven fix rounds (decisions 44 to 51, cherry-picked 3eff861, 9f10f88,
  ded60c8, b7dcdd8; bindings 1e97a36), the Function 2f77a87 plus security fix 65f8b7a (decisions 47, 48), Android d9b0d59,
  iOS b135e9a, shells follow-up eae0356 (Android) and e7336fa (iOS: decision 45 read-only group). Now: whole-branch
  review, the paired look, records drafting. At the stop: `.drafts/stop/DECISIONS-TAKEN.md` (drafted) becomes
  `docs/DECISIONS-TAKEN.md`; write the Fable opener.
- D-229 whole-branch review With fixes (decision 52); look PASS on both passes and all checks (findings F1 iOS tab bar
  once missing on Settle up after joining from Balance, F2 empty-state copy when the only group is read-only and INR vs
  USD on the empty Add expense: release-gate list). Fix wave: Functions acf37a7 (197 emulator tests), Android b23b8ba
  (JVM 916), iOS e6f1690 (hosted 751). Device check owed at the release gate: the "You're already in this group." line
  and the lost-place copy page without "Remove from this phone". The FENRIR deck dev server (orphaned since 15 Oct) was
  stopped with the founder's yes. Next: fix re-review, full gate, records, merge, then the stop.

**Decided while you were away (session 6; continues the list in the session 5 block, each overturnable in one line):**
31. D-226 Android's Aurora scroll frame time stays about +4.5 ms at the 90th percentile over 87b1be8 on the emulator
    (28 vs 23.5 ms), checked on a real Android at the 0.5.0 release gate. Reason: two fixes were built and measured
    and neither moved it (the blur's own copy at 15 and 7.5 fps); the cost is each card drawing the blurred art while a
    scroll is in flight, and the remaining options cost memory (a bitmap per card) or freeze the art during a scroll.
    An emulator's software path is not a phone's GPU, so the real number decides whether a unit is owed.
32. D-226 Android's Aurora dark bar band stays the flat #53686A it was matched to. The re-look found iOS's band is not
    flat (#516567 at one end to #55716F at the other, #56706F beside the title); Android's colour lies inside that
    range, and the title holds 5.25:1 (iOS 4.78). Reason: iOS's band is the system material and moves with what
    scrolls under it; chasing its one pixel beside the title would cost Android contrast for no visible gain.
33. D-216 Android's App icon caption drops its second sentence ("Some home screens remove the old icon; SKIPTI stays in
    your apps."): the look found Launcher3 keeps the home-screen icon through the switch (redrawn as the new icon,
    still opens the app, survives a launcher restart). The plan's own rule; both phones now read the same caption.
    OEM launchers (One UI, MIUI) are unverified and recorded as residue.
34. D-216 the icon changes when the Appearance page CLOSES, never when it is only covered (an alert, the app lock, a
    push). On Android the page also closes when the lock returns or a link opens; on iOS those only cover it. A tab
    switch cannot happen on either (the tab bar is hidden on pushed pages).
35. D-227 the role-asking rule on both phones also (a) ignores a refresh that ends after sign-out or after its group
    left, and (b) asks once more when the group changed while a refresh was in flight. Reason: this unit exists to
    make the Owner pill right; without (b) a role change made during a refresh stayed wrong until the next change or
    a relaunch, and without (a) a new session could skip its first refresh.
36. D-227 on the server, when the creator's place (from the creator's first create op) and another member's accepted
    invite name the same place, the invite wins. Reason: the security review found that a creator running a modified
    client could write its first create after someone joined, contest the joiner's place and make both unremovable;
    an accepted invite is written by the server, a create op by a client, so the server's evidence decides.
37. D-227 the creator holds its place through the creator path only while no removal record ever named that place; a
    removed creator who comes back holds through an invite like anyone else. Reason: otherwise any member writing
    addMember for the old place could hand the creator's powers back.
38. D-227 a failed role check (the groupRoles call fails or answers before the group is known) is asked again on a
    timer, after 15 s, 60 s and 300 s, as well as on the next change or return to the app, on both phones. Reason: the
    look saw a creator shown as a member for 68 to 110 s when only the role call failed; three spaced retries close
    that without a polling loop.
39. D-227 while the server does not yet know a group's owner place (the create has not uploaded), the Owner pill
    follows the old rule (what 0.4.0 shows) on both phones. Reason: the review found the place answer could stay empty
    after the create landed, showing the owner as a member; the old rule is display only and was right for one phone.
40. D-227 a profile share that failed (the cloud's function was down) is retried after 15 s, 60 s and 300 s on both
    phones, not only at the next return to the app. Reason: the re-look saw a share wait for the next foreground after
    the functions came back; the same schedule as the role retries (38) keeps one rule.
41. D-228 the two generated bindings files keep the three em dashes UniFFI 0.32's own templates write into its doc
    comments: both files join the grandfathered list with a three-entry baseline, so a new dash from SKIPTI's doc
    comments still fails the guard. Reason: the bindings are generated and never hand-edited (CLAUDE.md); rewriting the
    generator's output after each run would be a hidden step, and these comments are never shown to anyone.
42. FOUNDER (chat, 2026-10-18): the em dash rule is for the PUBLIC, front-facing side only (what people see: the
    apps' text, the website, store notes, public docs). The repo is private, so code comments, tests, scripts,
    internal docs, CLAUDE.md, AGENTS.md and commit messages are out of scope. D-228 therefore keeps what Tasks 1 to 4
    already swept (done and proven harmless), does NOT sweep CLAUDE.md and AGENTS.md, and its guard checks only the
    public surface (app string literals, resources, manifests, public docs, Hosting pages), with no grandfathered
    baseline; the commit-msg hook keeps only the attribution check. (Supersedes D-228 amendments A3, A8, the dash part
    of A4/A9, and decision 41, which is moot without a baseline.)
    Founder, same day: the repo goes PUBLIC at the move from internal builds to the public beta, and anything must be
    clean the moment it goes public. So the guard also has a `--scope whole-repo` mode (every tracked text file); it is
    not a gate leg today but the check to make pass before the repo is opened (on the public-beta list). His global
    ~/.claude/CLAUDE.md line was reworded to this rule at his request.
43. D-228 the money placeholder "-" is spoken as "none" by VoiceOver and TalkBack where it stands for a value (the Settled
    tile, the money fallbacks), and the spoken money fallback is silent on both phones (Android read "-"). Reason: a
    screen reader saying "hyphen" or "minus" under Settled misleads; the reviews found Android's spoken fallback differed.
44. D-229 when two or more claims on PRESENT members of a group name the same "you" id, that id gets no alias there:
    the person's records read as an unknown member, never as someone else's (removing the forger's member lifts it;
    decision 49). Reason: the core review found a member
    could claim an earlier, unclaimed member with a joiner's id and take the joiner's records; a denial is recoverable,
    a takeover moves money between people.
45. D-229 when an account's place in a group is held or contested by another account's claim (decision 36's race: an
    invite accepted before the creator's group reached the cloud; or any of the account's ids claimed by someone else),
    that group turns read-only on the creator's phone with a plain explanation,
    instead of writing records that would read as the other person's money. Reason: misattributed money is worse than
    a paused group; it can only happen in a narrow offline race.
46. D-229 the server's alias repair (`shareAliases`) treats an id as contested when any CURRENT OR FORMER member's own
    log holds it, not only current members (the plan said current). Reason: otherwise a member who stayed could copy a
    removed person's old id and take that person's records onto their own place; counting former members only adds
    refusals, never a takeover.
47. D-229 an alias can never name an id that belongs to another account: the server refuses it (any other account's
    participant id, member or not), and each phone ignores a marked alias that any claim naming that id on another member contests (a phone cannot tell
    who wrote a claim, so it must treat every such claim as possibly the owner's).
    Reason: the security review found a member could pre-alias a FUTURE member's id onto their own place, so that
    person's payments would read as the attacker's; two layers because either alone has a gap.
48. D-229 the alias repair is charged against a daily quota like the people functions, reads the caller's own log first
    and each member's log once per call. Reason: one unbounded call could cost tens of thousands of reads.
49. D-229 decision 44's contest counts only claims on members still in the group; otherwise an id's alias follows the
    old rule (the first claim in member order, removed members included). Reason: the re-review found that a person
    removed and invited back has an old claim and a new one naming the same id, and 44 as first built made their whole
    history unknown and their group read-only; with 49 their history stays on their old member (as the removal's
    redistribution expects) and new records go to their new place. The narrow case left (a forged claim on a REMOVED
    earlier member) goes to the security audit.
50. D-229 a claim on a member who has left the group counts toward an id's alias only if it was written before every
    claim on a current member naming the same id. Reason: the third review found a forger could claim with a victim's
    id and then have its own member removed, crediting the victim's payments to the forger; an honest rejoin's old
    claim is always older than its new one, so rejoins keep working. Only a backdated forgery remains (security audit).
51. D-229 an id's alias goes to the earliest-written claim that still counts, never by the order members were added.
    Reason: the fourth review found that when nobody present claims an id (the person left, or between a removal and a
    rejoin) the member-order rule let a later claim take the history; the earliest claim is the person's own.
52. D-229 the server refuses an invite from someone who already holds a place in that group, and the phones say
    "You're already in this group." Reason: the whole-branch review found one person tapping a second invite into a
    group they are in made their two claims contest each other (decision 44): their history read as unknown and their
    own phones locked the group. A group where your place is lost also hides "Remove from this phone" (you are still in
    it, and it would erase records waiting to upload): this reverses the session's earlier call to keep it.

## (previous) NOW: 2026-10-18 (session 5, close): D-223 to D-225 MERGED, D-226 IN PROGRESS. NEXT SESSION STARTS HERE (READ THIS FIRST)

**RESUME (session 6 starts here; the founder's opener is `.drafts/d226/next-session.md`):**
- **D-226 A art and heroes: IN PROGRESS on branch d226-art-heroes (pushed), NOT merged.** Done and reviewed: Task 0
  (before pictures), T1 values both shells d4e9d01, T2 iOS 9332dc6, T3 Android 1890e60 (each task-reviewed), the Opus
  whole-branch review (`.superpowers/sdd/d226/final-review.md`), the before/after look T4
  (`.superpowers/sdd/d226/look-report.md`; the founder's Q8 a pairs: `.drafts/d226/look/pairs/`, 43 images), the fix
  wave iOS 1768d20 and Android 89bcef2 (`.superpowers/sdd/d226/fix-report.md`). Records drafted in
  `.drafts/d226/record/` (apply.py refuses until the gate log and the re-review / re-look placeholders are filled;
  `.superpowers/sdd/d226/records-report.md`).
- **D-226 next steps, in order:** (1) Android frame time: still +4 ms at the 90th percentile (30 ms vs 87b1be8's 26 on a
  quiet machine; the cause is the art blur redone every art frame); build the next cheapest option, a separately
  blurred copy of the art refreshed at 15 fps, and re-measure with the look's method (`.drafts/d226/look/t4/`); if still
  over 2 ms, accept and record it with a real-device check at the release gate. (2) Android F2: the bar band's colour
  is teal-grey but 13 L* darker than iOS (#455857 vs #53696A); the Android fixer found an exact match, a Darken cap at
  iOS's colour that holds 5.2:1 over any content: adopt it (it keeps the floor, which was the reason for the dim). Both
  in one small Android fixer. (3) Fix re-review (Opus) of 1768d20, 89bcef2 and the new commits; (4) re-look (Opus):
  the hero edge, the bar colour and contrast, the shadow, the dark fill, the ink tops, frame time; (5) full gate;
  (6) records: fill the placeholders, add new lanes to `sabtable.py` LANES and commits to CITED; models: task reviews
  Sonnet, fix lanes Opus; (7) merge --no-ff, push, confirm no CI; close-out: delete build products and the two
  simulators' poster caches (CLAUDE.md 4).
- **Then, in order:** D-216 the app icon (`.drafts/d216/plan.md`, A1; Task 0 done, layers in `brand/skipti-icon-assets/`
  untracked until D-216 commits them; the records REPLACE the D-216 reservation row in place), D-227 the two bugs
  (`.drafts/d227/plan-v2.md` ADOPTED, not plan.md), D-228 the em dash sweep (`.drafts/d228/plan.md`; re-run its
  `inventory.py` first: D-223 to D-226 changed many files), D-229 the write split (`.drafts/d229/plan.md`, A1). Then stop
  at the 0.5.0 release gate.
- **Process lessons this session (all in `.superpowers/sdd/run-common.md` or CLAUDE.md):** sabotage runners boot
  AFAF4221 once and pass `-parallel-testing-enabled NO` (parallel testing runs on a clone and shuts it down); delete a
  unit's build products and the simulators' poster caches at close-out; check `df -h /System/Volumes/Data` (under 30 GB,
  clean first); FENRIR is never touched from a SKIPTI session without the founder's yes; Android launches with
  `--groups-proof` alone (it ignores the proof after `--emulators`); a look device may come up signed in (reset the
  throwaway keychain); Gradle in a sabotage worktree: build cache, file watching and daemon off.
- **Founder picks this session (2026-10-17 and 18):** decisions 19 to 24 confirmed; D-225 A6 and A8 (all four
  add-expense hints on both phones), A7 (Android vault-recovery screen), A9 (Try again); the cleanup rule; the
  poster-cache deletions; every AVD kept.

- Founder said "start the run" (2026-10-17). Order: D-223, D-224, D-225, D-226, D-216, D-227 (plan-v2), D-228, D-229,
  then stop at the 0.5.0 release gate. Ledger: `.superpowers/sdd/progress.md` "Session 5 run" (newer than this block).
- **D-223 Home MERGED** (records: row, section 3, residue, changelog; gate 22/22 4m41s at 0b00b16; 99 sabotage rows,
  97 exact, 1 broader, 1 equivalent mutant). Routed residue: Aurora dark glass stroke and the Android Aurora dark bar
  wash-out to D-226; the expense "Added by" stamp wording and Android's other tabs losing scroll offset to D-225.
- **D-224 E3 done moments and haptics MERGED** (records; gate 22/22 4m10s at 2eee496; 120 sabotage rows, all exact).
  A privacy hole found by the re-review and fixed: Android drew the done toast over the app lock screen (SPEC 3.7).
  Owed at the release gate: haptics and VoiceOver / TalkBack on real phones (simulators cannot).
- **D-225 S2 settings screens and form twins MERGED** (records; gate 22/22 5m11s at b0d094c; 178 sabotage rows, 177
  exact, 1 broader accepted). Android gained the vault-recovery screen (it had none) and both phones "Try again".
  Section 5 incident 29: the disk filled with old look caches; the cleanup-after-merge rule (CLAUDE.md section 4).
- **Disk (founder, 2026-10-18):** AFAF4221's 23 GB poster cache deleted (sim 29 to 6.4 GB), the unused "iPhone 17 Pro"
  simulator deleted, d214-look's poster cache to delete after D-226's look; every AVD kept. Now a close-out step (CLAUDE.md 4).
- Decisions made this session continue the "Decided while you were away" list below from 19.

**Decided while you were away (session 5; continues the list below, each overturnable in one line):**
19. D-223 Lately's day-and-month date ("16 Oct") formats on the app's one date axis (en-US / en-IN, `AppLocale`), not
    the phone's own locale as the plan's Design 6 said. Reason: every other date in the app uses that axis (D-54,
    D-221); two rules would show "Oct 16" in one card and "16 Oct" in the next on an en-GB phone.
20. D-223 Android's "People in X" link on a Groups card is laid out at 44 dp (was 48, D-220 I1), so the plain card
    matches iOS's 136 pt instead of 140.2 dp. Its touch area is at least 44 dp, widened to 48 by Compose where no
    neighbour claims the space (the card row above is clickable, so not on its top edge). Reason: the twins must look
    identical; 44 is iOS's own minimum and the extra 4 dp was visible.
21. D-223 Lately's "See all" uses `accent`, not `groundAccent` (amendment A1, session 4's controller): the link sits on
    the card's surface, where every other in-card link ("People in X") is `accent`; `groundAccent` is for text on the
    Aurora page ground and read grey (= textDim) in Aurora light in the look.
22. D-223 the Groups photo card follows iOS's structure and height (190.67 pt with the "People in X" link; Android was
    176.76 dp): iOS is the scale for measured twins, as in every unit of this run. The plan's 168 target predated the
    link.
23. D-224 the done overlay's dim covers the page's ground as well as its content on both phones, as iOS draws it; the
    tab bar and the + stay bright (E4's celebration keeps its content-only dim, which is already the same on both). Reason: the look found Android dimming only the content,
    so the bright Aurora ground and "Settle up" showed through the moment's words; iOS is the scale.
24. D-224 where the shells behaved differently, both take the quieter behaviour: the first-group buzz only when the
    overlay is seen, a check screen buzzes once per moment, a red-button removal screen warns once per presentation
    (not again on Back), one announcement per moment. Reason: twins must match, and a repeated buzz or warning is noise.

**Founder picks, 2026-10-17 (in chat, session 5):** both phones show the add-expense hints (shares example and
payer-only), D-225 A6; Android gets the twin of iOS's vault-recovery screen in D-225, A7. All four add-expense hints
(Percentage, Adjustment too) on both phones, D-225 A8. The vault-recovery screen gains "Try again" on
both phones (erase stays the confirmed last resort), D-225 A9. Decisions 19 to 24: all
CONFIRMED by the founder (pop-up, 2026-10-17).
25. D-225 the expense's "Added by" time follows the phone's own 12 or 24-hour setting on both phones (iOS already did;
    Android pinned 12-hour). Reason: it is the person's own preference, separate from the en-US / en-IN date axis.
26. D-225 every section label takes `textDim` on both phones (iOS's People, Invite, Add expense "Split", New group
    and Your name used `groundFaint`). Reason: a section label names what follows and is not faint text; Android's
    shared `SectionLabel` already defaults to `textDim`; the two tokens are equal in Aurora (the only theme with moving
    art under the ground), so no contrast floor moves.
27. D-225 the vault-recovery screen's confirmed erase also signs out of the cloud session on both phones, as the
    deliberate erases do. Reason: the account's owner key lives in the vault; a session left behind after the erase
    would point at a ledger that no longer exists. The person can sign in again afterwards.
28. D-226 every themed card's border is drawn INSIDE its shape on both phones (iOS straddled the edge, so Norse cards
    measured about 1 pt taller than Android's). Reason: a card's drawn size then equals its layout size on both, and the
    rows inside line up the same; iOS's `strokeBorder` is the idiomatic way.
29. D-226 the Norse hero's outer edge, like every Norse card and photo frame, is drawn on the straight sides only
    (not the diagonals), on both phones. Reason: your Q18 pick (notch edge on the straight sides only) read as one rule;
    a hero with lit diagonals beside cards without them would look like two styles.
30. D-226 the iOS Norse page glow keeps its 1-step bands (visible only at 16x contrast; Android's is dithered).
    Reason: four dithering attempts left the rings, and the two that would work cost 9 to 22 MB of memory for a
    difference invisible at normal contrast. Recorded as residue.

## (previous) NOW: 2026-10-17 (session 4, close): D-218 to D-222 MERGED. NEXT SESSION STARTS HERE

**RESUME (session 5 starts here; the founder's opener is `.drafts/d222/next-session.md`):**
- **Merged and pushed, nothing deployed (0.5.0 rule):** D-218 the D-210 leftovers (a8ca111), D-219 C1 tab bar and +
  (92ba575), D-220 C2 shapes and chrome (9a7e8d7), D-221 E4 ring, meter, celebration, milestones (8ca2560), D-222 S1 You
  list and Appearance (merge recorded in the ledger). Every unit: plan, SDD tasks, per-task review, Opus whole-branch
  review, paired look on throwaway devices, one fix wave (two shells in parallel), re-review, full gate 22/22, records via
  `.drafts/dNNN/record/apply.py`, merge --no-ff, push, no CI run.
- **Next, in order, every plan already written (copy `.drafts/dNNN/plan.md` to `docs/superpowers/plans/` at the unit's
  start; each carries "Controller amendments" at its end that bind it):** D-223 Home (`.drafts/d223/plan.md`, A1 to A3),
  D-224 E3 done moments and haptics (`.drafts/d224/plan.md`), D-225 S2 settings and form twins (`.drafts/d225/plan.md`,
  A1 to A3), D-226 A art and heroes (`.drafts/d226/plan.md`), D-216 the app icon (`.drafts/d216/plan.md`, A1; Task 0 DONE:
  six new layer files exported from Affinity into `brand/skipti-icon-assets/`, untracked until D-216 commits them; the
  records REPLACE the D-216 reservation row in place), D-227 the two bugs (`.drafts/d227/plan-v2.md` ADOPTED, not
  plan.md; design review `.drafts/d227/design-review.md`), D-228 em dash sweep (`.drafts/d228/plan.md`, A1 to A5; re-run
  `inventory.py` first), D-229 the write split (`.drafts/d229/plan.md`, A1). Then stop at the 0.5.0 release gate.
- **How a unit is run (keep doing it this way):** every dispatch points at `.superpowers/sdd/run-common.md` (the binding
  rules, with every lesson so far: worktree sabotage lanes, xcodebuild stderr, `--emulators` on every look launch via
  `xcrun simctl launch`, `git commit -- <path>` commits the whole file, stalls) and the brief from the skill's
  `task-brief` script moved into `.superpowers/sdd/dNNN/`; models per the founder (2026-10-16): Opus for judgment work
  (multi-file and drawing implementers, looks, fix waves, whole-branch reviews, records), Sonnet for task reviews and
  single-file mechanical work. The SDD ledger `.superpowers/sdd/progress.md` has a line per task.
- **Owed at the 0.5.0 release gate (add to the list below):** device checks: TalkBack and VoiceOver announcements (the
  celebration, the drawn controls), one possible 0.65 s iOS stall at the celebration's start, the Aurora blur's frame cost
  on a real Android (emulator p50 24 ms vs 17 ms), the API 26 to 30 blur fallback; read-only production checks with your
  yes: D-210's list, plus D-229's count of records written under an older id before `shareAliases` deploys; notes for you:
  Settle up says "all settled" when only YOU are square (against the group meter), "₹450 still to settle" beside "2
  payments to go" (nets vs pairs), the iOS nav title truncates after a text-size change (pre-existing; needs its own unit).

## (previous) NOW: 2026-10-16 (session 4): THE RUN after D-217, unit by unit without the founder

**Decided while you were away** (each overturnable in one line; the reason is also in the unit's BUILD-LOG row):
1. D-numbers for the run: **D-218** the two D-210 leftovers (one unit: they are one item in your list, both small, and
   both ship with 0.5.0's cloud deploy), **D-219** C1 tab bar and +, **D-220** C2 shapes and chrome, **D-221** E4 ring,
   meter, celebration, milestones, **D-222** S1 You list and Appearance, **D-223** Home, **D-224** E3 done moments and
   haptics, **D-225** S2 settings screens, **D-226** A art and heroes, **D-216** the app icon (replaces its reservation
   row in place), **D-227** the two bugs of item 13, **D-228** the em dash sweep, **D-229** the write split since D-153 (item 13).
2. D-218 zero-net loops: a loop is ANY directed cycle in the pairwise view, cancelled by its smallest edge (loops taken
   in ascending id order, deterministic, done once in core after D-210's collapse). The "all edges equal" reading would
   leave a realistic Settle-all case on screen. Balances and totals are unchanged by construction.
3. D-218 held places: createInvite, inviteUser and inviteKnown refuse with ALREADY_EXISTS, "Someone has already joined
   as this person.", before any write or quota charge (the phones map refusals by status; the other statuses already
   mean something else on these paths).
4. D-218 CLOUD-SECURITY: the "row the same day" is an "Also pending at 0.5.0 (D-218)" paragraph today, as D-210 and
   D-211 did; the dated section 2 row is written on deploy day (the document's own rule: no section 2 row until live).
5. D-219 (C1, planned in `.drafts/d219/plan.md`): every + face gets one 64 x 64 footprint so every bar is 70 pt (Norse's
   diamond box made its bar 76.7, taller than the 72 the pages leave clear, so its last row sat under the bar); the
   diamond is side 64 centred on the circle's centre; the + glyph stroke 2.8 (LB line 335), icons 2.4 through one
   `LucideStroke` constant per shell; the solid Norse/Vardr bar uses the existing `surface` token; the Vardr reticle is
   SKIPTI's own drawing (no NOTICE change); Vardr tab labels stay in the body face (the audit's correction: the LB's
   `.tab` overrides the Plex Mono at its line 283).
6. D-220 (C2, planned in `.drafts/d220/plan.md`): Norse pills notched at 4; Vardr Labs pills outlined (filled with the
   page colour on the page ground); Vardr Labs panels square everywhere; photo bands and Groups photo cards take the
   card's frame (white marks in Vardr Labs); Norse diamonds at the full tile size, letter 0.42 on every tile shape;
   blue replaces violet IN THE SAME SLOT (no other group changes colour); Norse and Vardr Labs bars solid on both
   phones, Android's Aurora bars blur on API 31+ (20 dp, 0.72 film; 26 to 30 keep the 0.94 film); Android dims
   disabled buttons to 0.45 as iOS; Android's currency list is a pushed page like iOS's; Lucide `search` and `check`
   replace the old glyphs (NOTICE ten drawings -> twelve); C2 takes L72 (iOS About art ground) from S2 and L-D217-2
   from Home.
7. Vardr Labs light: accent text over the grid's dots measures 4.12 (below 4.5), pre-existing. Your rule "every floor
   holds on 8-bit pixels" decides it: C2 re-derives the Vardr Labs light ground accent through palette.py until it
   holds 4.5 on the dot pixel, measured by the AppearanceTests. Overturn if you would rather thin the dots.
8. D-221 (E4, planned in `.drafts/d221/plan.md`, one D-number, three task groups: You, group page, celebration):
   the settled meter is money marked paid over money owed, per currency (a new core `ffi_settled_progress`); the
   currency step counts once a currency is chosen; Norse's 24 runes spaced evenly round the circle, lit by rounding
   half up; a group's next card "Settle" records in place like Settle up's cards, with the "does not move money"
   sentence; a compact primary button on both next cards; a milestone shows for 14 days after it is reached, no "seen"
   mark, no new stored state; celebration particles in the theme's ring colour and its second line in the text colour
   (the dim colour fails 4.5 over the dimmed page); the overlay never takes a touch (GUARANTEES #2). "Golden for Norse":
   the number in bone and the bar in gold, as the approved E4 board draws it; say if you meant the number itself.
   Q2 a (avatars in the Look Book's colours) is built in E4 where it touches the ring; Home takes the feed avatars.
9. D-222 (S1, planned in `.drafts/d222/plan.md`; the full list of 11 in `.drafts/d222/plan-report.md`): Look Book px
   scaled by 1.28 (tile 33, swatch 59, mode track 40 in a 48 touch row, slider track 5, thumb 20); one 48 touch row on
   both phones; unchosen mode words dimmed (the chosen segment differs from its track by only 1.05 to 1.24); Norse's
   disabled mode control shows Dark chosen; the slider takes each theme's shapes; the Norse swatch shows its runes in
   every theme; About's row drops the build number ("0.4.0 ᛫ promises ᛫ licences"); the phone count is one read of your
   devices list per visit to You, left out when 0 or unreadable; VoiceOver and TalkBack get native semantics under the
   drawn controls. The sample line "Dinner at Thalassa" uses YOUR default currency (the stored one, else your phone's region currency, else
   USD: the app's own rule), not always rupees.
10. D-223 (Home, planned in `.drafts/d223/plan.md`): the hero's who-owes line names the two largest people in the hero's
   currency; ledger cards read "Owed" / "You owe" / "Settled", no meter on a group with no expense; Lately shows the
   newest 3 entries, hidden when empty, with "See all" opening the whole-ledger Activity screen (orphaned since D-196);
   one relative-time rule on both phones ("now", "N min", "N h", "1 day", "N days", then "16 Oct"), Recent activity
   included; the grid drops to one column at accessibility text sizes; Groups cards spaced 12 on both; one drawn
   Expenses empty state (Lucide `inbox`) with SPEC 11.6's words.
11. D-224 (E3, planned in `.drafts/d224/plan.md`, 12 decisions in its plan-report): Haptics ON by default, stored with
   the theme preferences, a row under Text size (Lucide `vibrate`, NOTICE eighteen drawings); Android haptics through
   `performHapticFeedback` (CONFIRM / REJECT from API 30, CONTEXT_CLICK / LONG_PRESS below), no VIBRATE permission; a
   warning haptic when a destructive confirmation OPENS, not on the final tap; the big check drawn in `accent` (104 in
   E4's ring box, Norse adds the lit rune ring turning once per 14 s); the toast lasts 3.0 s, 104 above the bar's bottom;
   Add expense drops its 0.6 s "Saved" hold and closes at once (GUARANTEES #2); no haptic on the milestone card.
12. D-225 (S2, planned in `.drafts/d225/plan.md`, one D-number although it is L): it also takes every form twin no other
   unit owns (Add/Edit expense, New group, Your name, Profile's field and emoji grid, Group settings, the remove cross,
   the lock screen, every switch); a drawn `ThemeSwitch` on both phones; every form choice opens a choice page (Q15 a
   extended; the same number of taps as a menu); the Erase row opens a page that keeps today's alert; the emoji grid six
   columns (eight gives cells under the touch minimums); iOS `Form`s in scope become list cards so the themes can shape
   them; every erase, delete and app-lock-off confirmation stays word for word.
13. TWO REAL BUGS found by the D-218 look's follow-up (`.drafts/investigate-groups-tab/report.md`), both pre-existing:
   (a) iOS only: a group created from Balance does not appear on Groups, Expenses or Settle up until something else
   bumps the sync revision (iOS keeps all four tabs alive; Balance's completion never bumps it); (b) both shells: one
   account on two phones disagrees about who "you" is (server: `users/{uid}.participantId`, the first phone's; phones:
   D-153's earliest `you`), so the owner sees no Owner pill and no owner actions, and `shareProfile` skips every group.
   DECIDED: the SERVER's participant id wins (the phone adopts it as "you", its other ids become aliases; core pins
   "you"), because the server is the authority on membership (`placeIn`, the rules) and the narrow fix would leave
   `shareProfile` broken. It changes D-153's "earliest you wins" for signed-in phones only. Unit **D-227** (both bugs),
   after A; the em dash sweep moves to **D-228**. Both ship in 0.5.0, so the order inside the run does not delay them.
   REVISED after an Opus design review (`.drafts/d227/design-review.md`, plan `.drafts/d227/plan-v2.md`): NOT "the
   server's id wins" (adopting it would split groups made under the old id: other members' phones refuse the new id).
   Instead the SERVER stops using `users/{uid}.participantId` for a person's place in a group: the creator holds the first
   member of the first `create` op `createdBy` wrote, joiners hold their accepted invite's place, read from the log per
   request (no stored field, no backfill, no rules change); `groupRoles` adds `places: {owner, admins, you}` and keeps its
   old fields for 0.4.0 builds; the phones compare places. It also closes an access hole found by reading the code (a
   second account copying an unjoined place's participant id could hold that place). Still broken and NOT in D-227: the
   write split since D-153 (records written under an adopted id are refused on other members' phones in a group made
   under an older "you"): its own unit **D-229** before the 0.5.0 deploy.
16. D-228 (em dash sweep, planned in `.drafts/d228/plan.md`; ~56 user-visible sites, ~500 in comments): the "no value"
   placeholder becomes a plain hyphen (one constant per shell); GUARANTEES.md gets punctuation-only edits in three
   headings (no promise changes); CLAUDE.md and AGENTS.md are swept too (your rule: anywhere); BUILD-LOG, WORKLOG, ADRs,
   specs and plans stay as history; the gate prints "GATE PASSED: N passed" from then on; a cheap gate leg plus a
   `commit-msg` hook keep em dashes out; twin copy gaps it found go to S2.
18. D-229 (the write split, planned in `.drafts/d229/plan.md`): phase A, a phone writes each group's records under the id
   that GROUP knows it by (core computes each group's place; every record builder takes it), and `ownClaims` claims the
   place (stops a claim being re-uploaded on every push); phase B, existing records written under the other id are
   repaired WITHOUT rewriting them: a new Function `shareAliases` writes a server-checked `placeAlias` op (marked
   `alias: true`, which phones cannot write). Not "re-issue as edits" (rewrites history, cannot fix settlements). It
   also closes a hole: a member could claim an unjoined person using another member's id. A read-only count of affected
   records in production is on the 0.5.0 release list, with your yes, before `shareAliases` deploys.
17. D-221 group next card: the payment it offers AND the "The last one" / "N payments to go" line both come from ONE
   model, the group's PAIRWISE view after D-218's loop cancelling (what People cards and Settle up's cards show and
   record; corrected after the Opus review found Settle up records pairwise entries, not plan transfers), so the next
   card and Settle up always name the same payment; the first build mixed the pairwise view and the plan, so it could
   say "The last one" while another debt was open. Also: a quick second tap on Settle could record
   the same payment twice (the flag reset before the reload), fixed with Settle up's own button, which had the same tail.
14. D-226 (A, planned in `.drafts/d226/plan.md`): Q8 a art scaled 1.28x (chamfer 10, frame inset 4, frame arms 12 closing
   K10, grid pitch 18 with dot 0.77 so the tone holds, tile radius 3, diamond radius 8, ornament rune 17 in a 20 pt row,
   pill notch 5, panel cut 10 above 50 pt else 8; the Norse hero's inner frame and watermark scale too), before/after
   pairs per theme in `.drafts/d226/` for your emulator look (your Q8 "mock-up first"); Q7 a stars over the curtains,
   floors measured as D-214 does (curtains and stars separately: 4.61, 4.54); the single worst pixel, a star on the
   curtain's brightest part, reads textDim 2.98 (today's order 3.06), recorded as residue and on your look list, not a
   blocker; Norse `groundFaint` becomes textDim (#A69E89), D-214's Aurora rule, because a rune over the new gold glow drops
   textFaint to 3.91; **L30 Aurora hero shadow NOT built**: it breaks Aurora light's labels under the hero (2.72) and the
   strongest shadow that holds is invisible; Norse notch edge on the four straight sides for cards and photo frames only
   (not buttons, inputs, pills); Android glass gets iOS's film, rim and shadow with a 20 dp blur on API 31+.
15. D-216 (icon, planned in `.drafts/d216/plan.md`): with "Match theme" on, the icon changes only when you LEAVE
   Appearance and only if it differs (iOS shows a system alert on every change and nothing public suppresses it: at most
   one alert per visit, never one at launch; someone who updates while on Norse keeps the Aurora icon until their next
   visit to Appearance); Android launcher entries become activity-aliases IconAurora, IconNorse, IconVardr (permanent
   names; the update drops testers' existing home-screen icon once); the picker sits between "Light or dark" and "Text
   size": a "Match theme" switch (on) plus three icon rows, tapping a row picks it and turns matching off; Android adds a
   caption line iOS does not have (a twin difference by necessity); the manifest check learns a launcher contract and a
   self-test; the dead Lucide `bell` is removed; a debug-only `--notification-proof`; the six missing layer files
   (Norse and Vardr Labs adaptive layers, both dark iOS icons) are made in Affinity with the approved script at the
   unit's start; sources stay in `.drafts/d216/brand-src` (your words), not committed.

**Run state** (newest last; the SDD ledger `.superpowers/sdd/progress.md` has per-task lines):
- 2026-10-16: D-218 started on branch `d218-d210-leftovers`.
- D-218 plan committed 7256780 (`docs/superpowers/plans/2026-10-16-d218-d210-leftovers.md`, 4 tasks). Tasks 1 (core) and 2 (cloud) running in parallel; D-219 (C1) being planned into `.drafts/d219/plan.md` meanwhile.
- D-218 Tasks 1-3 complete and reviewed (23fc74a core, 7e9f532 cloud, 5aa6af5 iOS, cf2f519 Android; minors in `.superpowers/sdd/d218/minors.md`). Look (Task 4) and the Opus whole-branch review running. Sabotage: the per-task rows (core C1-C5 with C2b, cloud F1-F7, shells S1-S6 = 18, all exact) ARE the matrix; the fix wave adds its own rows; no separate runner (speed rule 2). FINDING: JVM tests load `target/debug/libskipti_core.dylib`, which `make android-core` does not refresh (`cargo build -p skipti-core`). C1 planned; C2 planner running.
- **D-218 MERGED** (see below for the merge commit): full gate run 1 21/22 (bindings freshness only: a doc comment reaches
  the bindings), bindings committed 8d58bfa, run 2 22/22 in 4m51s; sabotage 30 rows (25 exact, 3 wider, C2 an equivalent
  mutant); records in BUILD-LOG (row, section 3, residue a to k, changelog). NOT deployed (0.5.0). OPEN finding from its
  look, to investigate: on iOS after `--emulator-as` sign-in and a new group, no Owner pill on People and the Groups tab
  says "No groups yet" while Balance shows the group (Android fine).
- Plans ready in `.drafts/`: D-219 C1, D-220 C2, D-221 E4, D-222 S1, D-223 Home; E3 planner running. NEXT: D-219 C1 SDD.
- **D-219 MERGED** (C1 tab bar and +): gate 22/22 7m41s on 64f165c; sabotage 47 rows (44 exact, 3 wider, 0 survived);
  look 19 pairs + fix shots (Android diamond 6% small and back chevron small: fixed; + wins taps over its neighbours).
  Residue: iOS 26+ back chevron is the system's; Android bar 76 dp vs 70 (empty strip under the labels). All other plans
  ready: D-220 C2 .. D-226 A, D-216 icon (Task 0 assets exported from Affinity, in brand/, untracked), D-227 plan-v2.
  NEXT: D-220 C2 SDD.
- FOUNDER (2026-10-16, popup): subagent models: **Opus where it's judgment** (multi-file and drawing implementers, looks,
  fix waves, the big units E4, S2, D-227); Sonnet only for mechanical single-file tasks and task reviews. D-220 Task 1's
  Sonnet implementer stalled 25 min after its sabotage run (row V9 not red): stopped, an Opus finisher took over.
- D-220 (C2) on `d220-c2-shapes`: T1 144f995, T3 1dbba83 (Android call sites), T5 1633ecb (Android bars: Backdrop.kt blur
  API 31+, the tab bar now overlays the page as on iOS, currency list a pushed page), T2 24af357 (iOS call sites); all
  reviewed (findings for the final wave in `.superpowers/sdd/d220/minors.md`). T4 (iOS bars, currency) running, then T6
  look, Opus whole-branch review, one fix wave, gate, records.
- **D-220 MERGED** (C2 shapes and chrome): gate 22/22 4m52s on 9f2f072; sabotage 102 rows all EXACT at the final run; look
  90 pairs; two-lane fix wave (Android 6 commits, iOS 2). Residue in BUILD-LOG (iOS List/Form sections -> S2; blur cost to
  check on a device at release). NEXT: D-221 E4 (first commit: People.kt AddPersonCopy KDoc moved back).
- D-221 (E4) on `d221-e4-ring-meter`: T1 core `ffi_settled_progress` + bindings b3b99d7; T3 Android values 1efd48a; T2
  iOS values 542e9b7 (all reviewed or in review). T4 iOS You and T5 Android You running; then T6/T7 group page, T8/T9
  celebration, look, Opus review, fix wave, gate, records. D-228 (em dash sweep) planned meanwhile (decision 16).
- D-221 all nine build tasks committed (through c2ca852: T4 You iOS 9776d0b, T5 You Android d77182f, T6 group iOS
  ffce4c6, T7 group Android 5e0bac6, T8 celebration iOS c2ca852, T9 celebration Android 9cd39e8). Paired look (both
  devices on en_US) and the Opus whole-branch review running; the fix list grows in `.superpowers/sdd/d221/minors.md`.
  Celebration twin decisions: a settle seen while the group page is off screen plays when it is next on screen (both
  phones, no replay after relaunch); leaving the page mid-play ends it on both.
- D-221 reviews done: Opus whole-branch "with fixes" (10 Important) + paired look (75 pairs, both on en_US). Fix wave:
  stage 1 core clamp 03086e2 (bindings regenerated), stage 2 iOS and Android fixers running from
  `.superpowers/sdd/d221/fix-list.md` (the double-record latch on both shells and Settle up, pairwise GroupNext, 44 pt /
  48 dp targets, spoken money, Norse diamond layout, the date format, the twin gaps, the badge tint). Residue for you:
  Settle up says "all settled" when only YOU are square, which contradicts the group's new meter.
- **D-221 MERGED** (E4 ring, meter, celebration, milestones): gate 22/22 5m37s on 0cfc7dd; sabotage 146 rows, 0 survived;
  look 75 pairs; fix wave in 3 stages. Residue in BUILD-LOG incl. the Norse diamond 65.9 vs 67.7 (D-222's first fix),
  "₹450 still to settle" beside "2 payments to go" (nets vs pairs), announcements to hear on a device. NEXT: D-222 S1.
- D-222 (S1) on `d222-s1-you-appearance`: A2 Norse diamond edge centred on Android 56b762b; T1 values + 4 Lucide drawings
  87f3540 (NOTICE sixteen); T3 Android ebe68d8; T2 iOS fd1acee; the sample line uses the app's own default currency (stored,
  else the phone's region, else USD). Paired look, Opus whole-branch review and T2 review running. D-229 planned (decision 18).
- **OPEN FOR THE FOUNDER (possible production contact, D-220 T4):** the iOS look agent's FIRST launch on d214-look ran
  about 2 minutes WITHOUT `--emulators` while the app showed "Signed in". Evidence it was harmless: every earlier
  sign-in on d214-look was an emulator account (`--emulator-as`, D-218 look and the investigation), and production rejects
  an emulator-issued token, so reads and writes should have been refused. NOT verified: `gcloud logging read` needs
  `gcloud auth login` (expired; interactive, yours). To verify: Cloud Logging for skipti-26, 2026-10-16 ~17:00-18:30 UTC,
  any request or Function call from an unknown uid. Done meanwhile: the app is uninstalled on d214-look; every look
  launch now must pass `--emulators` and uninstall first (run-common.md). Recorded as a near miss in D-220's row.
  **RESOLVED 2026-10-16 (after the founder's `gcloud auth login`):** `gcloud logging read` for skipti-26 shows NO entries
  at all on 2026-10-16 (no Function call, no Cloud Run request; the query returns the usual daily purge entries on earlier
  days, so it works), and an emulator-issued token cannot pass production's signature check for Firestore. No production
  contact. Stays a near miss in D-220's row.

## (previous) NOW: 2026-10-16 (session 3, close): D-217 MERGED. NEXT SESSION STARTS HERE

**Decided while you were away:** (empty: the next session lists here every decision it makes for the founder, with
the reason, so he can overturn any in one line.)

**State:** main carries D-217 (one type scale on both phones, iPhone's way; Android's `SkiptiBar`; the type roles), merged
--no-ff and pushed; nothing deployed (0.5.0 rule). Full gate 22/22 in 3m48s (2026-10-16T03:41Z); sabotage 145 rows in
18 minutes in two parallel worktree lanes: 135 EXACT, 8 BROADER, 2 SURVIVED (predicted: I006, I036), 0 NOT OK; the look
63 pairs, size table D1 to D16 0 DIFF. BUILD-LOG: the D-217 row, section 3, section 4 residue, changelog, incident 28 (the
git stash near miss). **D-216 has a RESERVATION row** (the icon, designed 2026-10-15, not built): the D-216 unit replaces
that row IN PLACE through scripts/buildlog_edit.py (its old-string is the whole reservation row), it does not insert a new
one. CLAUDE.md section 4 now holds the founder's standing **Speed rules** (boot the iOS test simulator before any series of
xcodebuild test calls; sabotage sized to the risk; iOS and Android lanes in parallel worktrees, template
`.drafts/d217/sabotage.py lanes`; parallel agents never stash/reset/checkout/restore; check the machine first).

**Next, in order, WITHOUT waiting for the founder between units** (his instruction, 2026-10-16; the opener he pastes is
`.drafts/d217/next-session.md`): (1) the two D-210 leftovers (hide zero-net loops on People cards; refuse invites for a place
a member already holds, cloud refusals + a refusal test + a CLOUD-SECURITY row, deploying with 0.5.0); (2) the Look Book
units with the canvas picks (all recommended except titles A and Q6 C): C1, C2, E4, S1, Home, E3, S2, A, each also taking
its rows from consolidated.md "Found in the D-217 look" (L-D217-1 to 11); (3) D-216 the app icon (replace the reservation
row); (4) the em dash sweep. Then stop at the 0.5.0 release gate (security audit from CLOUD-SECURITY section 7 incl. G-08;
read-only production checks with his yes; deploy order rules, Functions, apps; versions and paste-ready notes; a TalkBack
check on a device (D-217's reading order is proven by guards only); his own look at the full art; the Developer Tools
exemption OFF before any store build). Deferred to 0.5.1: Just me, onboarding, operator push and the survey prompt.

## (previous) NOW: 2026-10-16 (session 3): D-217 STARTED on d217-type-scale (READ THIS FIRST)

Founder: "go ahead" (D-217 next). Scope (decided, consolidated.md "Founder picks"): titles Option A, iPhone's way on both
phones (tab roots large 34 left, collapsing to inline 17 centred; pushed screens "‹ parent" + inline 17 centred; Android
gets iOS's bar model); every size family takes iOS's value (look-report section 7: D1-D16, line height natural not 22 sp
fixed, avatar initial 0.38, share input 15, Android ellipsis on row titles); greeting 700 + Norse .02em (L05); T folded in
(Q13 a): a name role (body face 600) for names (L08), date line and hero kicker as kickers (L09), section headings as the
theme's section label (Q1 a, L10), tile initials display 700 (L11), You name 700 / handle 500 (L12); Norse sentence case
kept (Q14 a); L06 (Vardr kicker 12.4) left; L07 Norse ornament 42% rules pulled up under the large title.
Founder (2026-10-16): the two D-210 leftovers go INTO 0.5.0, right after D-217: hide zero-net loops on People cards (core), refuse invites for a place a member already holds (app never offers; createInvite/inviteUser/inviteKnown refuse; deploys with 0.5.0). Only Just me, onboarding and operator push are 0.5.1.
Founder (2026-10-16): onboarding deferred to 0.5.1 or the public build (no screen work on first-run surfaces in D-217).
SDD (ledger .superpowers/sdd/progress.md, D-217 section; artifacts .superpowers/sdd/d217/): plan has 9 tasks after the
screen inventory (.drafts/d217/screens.md). T1 80341a1; T2 fabef88 + fix 916d1a4; T3 1db1e62; T4 29746d1 + fix 0396899; T5 a3ecd0a; T6 18d90f6 + fix fd9d75c;
T7 6e79db0 + fix 9643551; T8 97bd83f (review running). All implementation tasks in. NEXT: T9 = Opus whole-branch review ->
one fix wave (incl. must-fixes in .superpowers/sdd/d217/minors.md: Join Cancel after join, people-list chevron colour, stat
tile shrink) -> full look both phones all themes -> sabotage (sized to risk, iOS + Android lanes) -> gate -> records -> merge.

## (previous) NOW: 2026-10-16 (session 3, close of D-215): D-215 MERGED (76ff62a on main, pushed, no CI run)

D-215 closed: sabotage 413 rows (354 EXACT, 47 BROADER, 9 SURVIVED all predicted, 3 NOT OK = real kills with a wrong second
prediction); full gate 22/22 (2026-10-16T03:02Z; cargo 298, iOS 434, JVM 477); BUILD-LOG D-215 row, section 3, section 4
residue, changelog, incident 27 (the export proofs); ADR-0041 "Type and shapes", the E2 spec and CLAUDE.md corrected
(G1 shapes, incident count, boot the simulator before many xcodebuild calls). Also in this commit: the rune guard's good
samples carry a rune (A132/A134), NOTICE without em dashes.
**Next:** D-217 (one type scale; titles = Option A, iPhone's way on both phones; Norse ornament placement for the inline
17 pt title to design), then C1, C2, E4, S1, Home, E3, S2, A per the founder's picks (consolidated.md, "Founder picks").
Then D-216 (the app icon). 0.5.1: Just me; operator push and survey prompt (brainstorm against GUARANTEES first).
Spawned chip pending: em dashes in Swift/Kotlin UI strings. Developer Tools exemption still ON: remind before any external build.

## (previous) NOW: 2026-10-15 (session 3): D-215 continues on d215-fonts (READ THIS FIRST, then the ledger's "Session 3" lines)

**PROOF FAILED found:** a D-211 regression, not fonts: D-211 moved the export to schema 4 and left both export proofs
checking `"schema_version":3,` (iOS ExportProofScreen.swift:118, Android Exporter.kt:237); no test ran either proof.
DONE: P1 013d4ca (tests on both shells run prove(); look on d214-look: EXPORT BYTES PROVEN). B1 complete (re-review 2 clean).
A3 complete; founder KEPT v1.000's nine post-approval glyph changes. B2 complete (ba1a810 + fix 02da7d6: Material type follows the theme's
body face, the Twins decision). B3 complete (3d3178c). B3b ec3a6aa (licence leg fails on a bundled font with no entry; review running).
B4 complete (96fcab4: iOS runes from Vardr Runes Carved via a Core Text cascade on every face; licence entry). B5 88492e4 (Android
rune spans, Normal weight, no synthesis; looks match B4); its fix (Add expense group line as iOS shows it, a D-213 twin gap) running.
B5 complete. B6: Opus review (ready after one wave) -> fix wave e2a7f6a/1d7c234/f89a132 (Material type untracked, segmented
picker in the body face, guards widened, Space Grotesk pinned, every font needs an Android copy) -> F2 0d9cf8f (Appearance
sizes identical, NOTICE dashes, undeclared Gradle test inputs fixed). LOOK done (71 pairs, .drafts/d215/look/): faces right
everywhere; 16 SIZE families differ (pre-existing) -> founder: new unit D-217 "one type scale on both phones" after D-215
(mock-up per family, titles decided there). Founder Norse requests -> G1 running (tab runes 22 -> 26, Norse buttons and
inputs notched like the cards).
**Next:** G1 review + Norse look for the founder; sabotage (.drafts/d215/sabotage.py, 332 rows, re-check anchors after G1);
full gate; records (.drafts/d215/record/apply_docs.py + BUILD-LOG b/i notes + D-215 row via record_unit.py; NOTICE's
11 older em dashes; section 4 residue incl. the D-217 size list); merge. Then D-217, then D-216.

**Founder plan (2026-10-15, after the Look Book audit, .superpowers/sdd/lookbook-audit/consolidated.md: 95 gaps):** merge D-215,
then D-217 sizes (+ type roles T), C1 tab bar and +, C2 shapes and chrome, E4 ring/meter/celebration, S1 You list and
Appearance, Home (Your ledgers, Lately), E3, S2 settings screens, A art and heroes. The 18 detail questions are answered in
an Artifact mock-up: PUBLISHED 2026-10-16, all 13 boards (https://claude.ai/artifact/RjbFy6vT4GKTKZYPXtzXiL, Design canvas;
sources .drafts/lookbook-fixes/canvas/project/; tab runes drawn at 26 px = 34 pt x 0.77). Founder picks (2026-10-16): D-217 titles = A, iPhone's way on both phones (34 large roots, 17 inline centred pushed); Q6 ray streaks = C (keep the mean, as today); every other question as recommended. Recorded in .superpowers/sdd/lookbook-audit/consolidated.md.
G1 is DONE (tab runes 34, Norse controls/panels/tiles notched; 24ca170, 9ec163f).
**Sabotage running (2026-10-15 20:42, .drafts/d215/sabotage/rows.log; 413 rows; baselines green: JVM 102, iOS 99 in the
five classes).** Finding: Gradle's cache clean-up removed the androidx.credentials 1.6.0 POM, so `licences.py --check`
fails on the CLEAN tree (the gate's licence leg would too). After the android rows: re-fetch (CLAUDE.md section 7:
`cd android && ./gradlew :app:dependencies --configuration playReleaseRuntimeClasspath --refresh-dependencies`), then
re-run P019 P020 P023 P024 P034 with `--rows` (their red was the POM line). A Sonnet agent drafts the D-215 records in
.drafts/d215/record/ meanwhile. **0.5.1:** the "Just me" personal ledger, and operator push messages (surveys,
announcements) with an in-app prompt (must fit GUARANTEES #1, #2, #5, #6: brainstorm first).

## (previous) NOW: 2026-10-15 (session 2, close): D-215 IN SDD on d215-fonts; D-216 DESIGNED, assets made (READ THIS FIRST)

**Read the RESUME block at the end of `.superpowers/sdd/progress.md` (it is newer and fuller than this block).**
**State:** main = D-214 merged (3ee0c24) + the D-215 spec (559a838) and plan (be37c85). Branch `d215-fonts` (pushed): B1 iOS
IBM Plex Sans 1e9a70e + fix d9f4185 + fix 2 ba13b3a (iOS 418/0). Fonts repo `vardrlabs/fonts` (private, local
`~/Documents/Vardr Labs/fonts`): A1 c817bdd, A2 8191d11 (all 89 runes, founder approved), A3 43abceb = release v1.000
(12 TTFs; Carved Regular sha256 6d61b17c...cf01, PostScript VardrRunesCarved-Regular).
**Next session, in order:** (1) the `--export-proof` screen showed PROOF FAILED on three JSON checks on the throwaway sim
`d214-look` during B1's fix 2: reproduce on main vs `d215-fonts` (systematic debugging) before anything else; (2) re-review B1
fix 2 and review A3 (sonnet), and show the founder A3's four moved dots (ᚡ ᚵ ᚧ ᛔ) and the new ᛀ; (3) B2 Android Plex Sans,
B3 licences/NOTICE/fonts README, B4/B5 runes in Vardr Runes Carved on both shells, B6 (Opus review, look, sabotage, gate,
records, merge); (4) D-216 the icon (after D-215 merges).
**Founder decisions this session (2026-10-15), beyond D-214's:** iPhone and Android LOOK identical, type included (CLAUDE.md
Twins); our own runes "Vardr Runes" (OFL 1.1, RFN "Vardr"; Carved for SKIPTI, Norse only; Machined/Circuit for other projects;
the full Runic block approved); IBM Plex Sans for Aurora/Norse body text; iOS system chrome (alerts, dialogs, menus,
ContentUnavailableView, menu Pickers) stays SF, recorded as residue; the new app icon = the cut S (v9 approved), one icon per
theme (Aurora default, Norse gold/silver, Vardr Labs steel/cyan on light), an App icon picker in Appearance with "Match theme"
ON by default, the status-bar glyph `ic_stat_skipti.xml` replaces the bell. Icon files: `brand/skipti-icon-assets/` (untracked,
commit in D-216), `brand/skipti-icon-affinity/` (local only, ignored via .git/info/exclude; add to .gitignore in D-216); sources
`.drafts/d216/brand-src/` (geometry, Affinity build script not yet run as one file, SVG generator). Affinity is a connector
(`mcp__Affinity__*`); notes in memory.

## (previous) NOW: 2026-10-15 (session 2, end): D-214 (E2 motion) RECORDED AND MERGED, not deployed; NEXT = D-215

**State:** D-214 merged into main (--no-ff) and pushed; nothing deployed (0.5.0 rule). Full gate 22/22 on ef0f2f3 (4m19s;
cargo 298, iOS 407, JVM 451). Sabotage 198 rows: EXACT 178, BROADER 7 (by design), NOT OK 0, SURVIVED 13 (12 expected look-only,
J61 an equivalent mutant) (`.drafts/d214/sabotage/table.md`). Three fix waves after the Opus review and the perf/look pass (bar tint
on Aurora light, stacked-curtain floors in the gate, 30 fps on one shared grid, art full-bleed on Android, cached symbols on iOS).
BUILD-LOG D-214 row, section 3, section 4 (a)-(s), changelog; ADR-0041 "Motion"; spec section 2 corrected in place; plan strengths
corrected (0.79, 0.60: the founder's 8-bit rule). Ledger "SDD ledger: E2 motion" in `.superpowers/sdd/progress.md`.
**Founder decisions this session (2026-10-15):** Norse runes 0.79 and Vardr light hero signs 0.60 (8-bit rule: every floor holds on
8-bit pixels); iPhone and Android must LOOK identical, type included (CLAUDE.md Twins); D-215 = the Vardr Labs font: our OWN runes
"Vardr Runes" (repo `vardrlabs/fonts`, private, local `~/Documents/Vardr Labs/fonts`, OFL 1.1 with Reserved Font Name "Vardr"),
styles Carved (SKIPTI, Norse ONLY; "carved with character": branches 0.70, waist 0.14, flare 0.50, bowed lone diagonals),
Machined and Circuit (other projects only), weights Light/Regular/Medium/Bold (Regular default); the whole Runic block U+16A0-16F8;
IBM Plex Sans for Aurora and Norse body text, button words and Aurora micro labels on both shells; fontTools may be installed
(pinned, build-time only). The Vardr Labs theme has NO runes (optional later: redraw its tab icons, not runes).
Specimen v5 approved ("looks good"): https://claude.ai/artifact/DmAUPuZbTTWd55LMgfx9dn, source `.drafts/d215/runes-specimen.html`,
copy in the fonts repo (79a0e44).
**NEXT:** D-215: spec (brainstorm short: what is bundled where, Dynamic Type, the licences page, NOTICE, the pinned font-file
test on both shells) -> draw the full Runic block in Carved (check every glyph against the Unicode chart AND a reference
rendering before showing him) -> build with fontTools in vardrlabs/fonts -> bundle Carved + IBM Plex Sans in SKIPTI -> SDD.
Then E3, E4, F, H; the pre-0.5.0 security audit; the founder tests the full art on an emulator himself before 0.5.0; Developer
Tools exemption reminder before any external build.
**Open for the founder:** D-214 look items (the Aurora hero's stars veiled by the curtains since F8, both shells; iOS 17-25 back
button proven by code and test only; Android jank metric and the 60 Hz frame-gate jitter to judge on phones at 0.5.0); the iOS
Aurora group-tile halo predates D-214 (its own item).

## (previous) NOW: 2026-10-15 (session 2): D-214 Tasks 1-6 DONE on e2-motion; Task 7 STARTED

**State:** branch `e2-motion` pushed at 51231e7. Tasks 2-6 built and reviewed clean this session: T2 f704f5b (Android engine +
Swift parity), T3 407ed78 (iOS renderer), T4 65d902e (Android renderer), T5 1860625 (iOS heroes, groundFaint/groundAccent,
moving-art floors; iOS counts 14/2), T6 51231e7 (Android twin; counts 10/18: Android draws its own Back/Cancel). Tallies iOS 401,
JVM 443, 0 failures. Ledger `.superpowers/sdd/progress.md` "SDD ledger: E2 motion" holds every task line and Minors N1-N33
(copied to `.superpowers/sdd/e2m/minors.md`). **Task 7 Step 1:** Opus whole-branch review writes `.superpowers/sdd/e2m/final-review.md`;
then ONE fix wave + re-review, performance, look, sabotage, full gate, records, merge.
**Controller findings this session:** (1) N27 stacked curtains: `.drafts/d214/stack.py` (real geometry, three curtains stacked):
as rendered (source-over on both shells) every Aurora floor holds (dark textDim 5.22, light 4.73 below the status bar); a TRUE
multiply on the light page would give 4.41 (below 4.5), so the blend "fix" must not go to true multiply at strength 1.00.
(2) N28: Norse page runes at 0.80 measure 4.4987 at 8-bit against textFaint (4.5013 exact); recommendation 0.79 (founder's 0.80
was the largest passing scale in exact maths; to tell him). (3) The bolt turn SEEN on iOS (the + turns with the hex, reads near an x).
**Look device:** the founder granted the iOS Simulator tool's access to throwaway sim `d214-look` (BDC0AE93-1CA7-4EE6-9C52-FA44D7C01F92):
KEEP it for Task 7's look, delete after. Agents' new throwaways were refused (access is per device). Taps need duration 0.15.

## (previous) NOW: 2026-10-15 (late night, session end): D-214 SDD IN PROGRESS on e2-motion; Task 1 DONE; NEXT = Task 2

**State:** branch `e2-motion` (pushed), main = D-213 merged (b6b4111) + the D-214 plan (79fcec1). Task 1 (iOS motion engine,
`ThemeMotion.swift` + `ThemeMotionTests.swift`, pbxproj A10205/B10205, A10206/B10206) built and reviewed clean: 75eb043 +
182c58c (hero strengths 1.0 after the founder's decision); iOS 394 tests, 0 failures. Ledger `.superpowers/sdd/progress.md`
"SDD ledger: E2 motion" ends with a **RESUME block: read it first**; it holds the next dispatch, the process, Task 1's Minors
(N1-N4) for the final fix wave, and every founder decision. Briefs `.superpowers/sdd/e2m/` (refreshed). Goldens and strengths
`.drafts/d214/motion.py` (run `python3 -I .drafts/d214/motion.py`). Demo of the strength decisions:
https://claude.ai/artifact/Qx6Z9d11ExHh7QniZ3NLKF (v3), source `.drafts/d214/curtains-demo.html`.
**NEXT:** dispatch Task 2 (Android engine + parity test; Sonnet), then 3-6, then Task 7 (controller) per the RESUME block.
**Open for the founder:** allow the iOS Simulator tool's device access when an agent asks (twice unanswered in D-213); he
tests the full art on an emulator himself before 0.5.0; D-213's open items below still stand ("By group" headings without
the mark; "No groups yet" right after creating the first group, not D-213's code).

## (previous) NOW: 2026-10-15 (night): D-214 PLAN WRITTEN; SDD STARTING on branch e2-motion

**State:** plan `docs/superpowers/plans/2026-10-15-e2-motion.md` (7 tasks: 1 iOS engine `ThemeMotion.swift`, 2 Android twin +
parity, 3 iOS renderer + page grounds + bolt turn, 4 Android twin, 5 iOS heroes + groundFaint/groundAccent + moving-art floors,
6 Android twin, 7 controller: review, performance, look, sabotage, gate, records). Goldens and strengths from
`.drafts/d214/motion.py` (ports the Look Book's formulas). Ledger "SDD ledger: E2 motion"; briefs `.superpowers/sdd/e2m/`.
Founder decisions on strength: below (2026-10-15 popup). D-213 is merged (b6b4111), see the previous block.

## (previous) NOW: 2026-10-15 (night): D-213 (E2 static art) RECORDED AND MERGED, not deployed; NEXT = D-214 plan (motion)

**State:** D-213 merged into main (--no-ff) and pushed; nothing deployed (0.5.0 rule). Full gate 22/22 on a454f39 (3m46s;
cargo 298, iOS 385, JVM 425 fresh). Sabotage 100 rows: 94 EXACT, 0 BROADER, 0 NOT OK, 6 SURVIVED on purpose (look-only
guards) (`.drafts/d213/sabotage/table.md`). Look on throwaway devices (`.superpowers/sdd/e2/look-report.md`), fix waves 1
and 2 re-reviewed clean. BUILD-LOG: D-213 row, §3, §4 residue (a)-(j), changelog. ADR-0041 "Theme art"; spec corrected in
place (light bolt stops, tiles reading, section-label mechanism, + floor, outline nudge). Ledger "SDD ledger: E2 static".
**Tell the founder (his to judge):** the iOS Simulator tool's device access went unanswered twice (agents fell back to
screenshots); the + press states, the outlined button's pressed tint and Vardr's System-mode title colour are for his
phones at 0.5.0; non-micro headings ("By group", "Who owes whom") carry no rune mark (spec reading, ask); "No groups yet"
seen right after creating the first group (iOS, not D-213's code; open as its own item); D-212's look left a video
recorder running (stopped with his yes).
**D-214 founder decisions (popup, 2026-10-15, demo https://claude.ai/artifact/Qx6Z9d11ExHh7QniZ3NLKF, source
`.drafts/d214/curtains-demo.html`):** the Look Book's curtains break the floors (dark page title 1.1:1); Aurora page text over
the art holds text 7:1 and labels 4.5:1, faint and accent text sitting on the Aurora page ground take the label colour
(tokens groundFaint/groundAccent); the hero measures each part alone (curtains 47%, stars 28%). REVISED by the founder the same night (demo v3, row "Where the hero's text sits"): the Aurora hero keeps today's layout with the Look Book's curtains and stars at FULL strength; he accepted the first line and amount at about 1.7:1 at the curtains' lowest swing (the band lives in the hero's top third); not floored, pinned by test. A 66% text shade was rejected ("a black box"). **Before the 0.5.0 release: the founder tests the full art on an emulator himself** (his words, 2026-10-15) and may revisit the hero strength then; hand him a build plus exact launch commands against the local emulators. Strengths from
`.drafts/d214/motion.py`: Aurora dark page curtains 0.26 stars 0.16; Aurora light page 1.00; Aurora hero 0.47 / stars 0.28;
Norse page runes 0.80 (peak 0.144); Norse hero 1.00; Vardr hero signs 0.61 light / 0.54 dark.
**NEXT:** superpowers:writing-plans for D-214 (motion: spec section 2; one engine per shell, still mode, Aurora curtains,
Norse rune drift, Vardr currency signs, the bolt's turn, performance on sim/emulator), branch `e2-motion`, SDD as D-213.
Then E3, E4, F, H; the pre-0.5.0 security audit; Developer Tools exemption reminder before any external build.

## (previous) NOW: 2026-10-15 (evening): D-213 PLAN WRITTEN; SDD STARTING on branch e2-static

**State:** plan `docs/superpowers/plans/2026-10-15-e2-static.md` (7 tasks: 1 iOS foundations `ThemeArt.swift`, 2 Android
twin + parity test, 3 iOS surfaces, 4 Android surfaces + raw accent buttons to PrimaryAction + `--rune-proof`, 5 iOS words
(section mark, separators, titles, ornament), 6 Android words, 7 controller). Measurements `.drafts/d213/art.py`
(Floors failing: 0). Ledger section "SDD ledger: E2 static" in `.superpowers/sdd/progress.md`, reports `.superpowers/sdd/e2/`.
**Plan decisions (controller's, record in D-213):** (1) the ᛟ mark is an opt-in `.sectionLabel()` / `SectionLabel`, not
inside `microLabelFont`/`kicker` (a third of those sites are status words, hints, row flags); (2) tiles = `PictureTile` +
`PersonAvatar`'s initial (Norse diamond, Vardr square); `AvatarView` stays round with the Norse ring; (3) ᛫ in the accent
only where a body draws the joined line; (4) Vardr light bolt uses the Look Book's light stops (spec listed dark only);
(5) NUDGE: Vardr light tab outline 0.75 not 0.50 (3:1 on the bar); (6) the + glyph floor measured under the glyph (the
spec's "lightest stop" is the wrong worst case for a dark glyph); (7) Android rune glyphs proved by a debug proof, not the
gate (no Robolectric); (8) seven raw accent Buttons on Android move to PrimaryAction.
**Progress (SDD, branch e2-static, not pushed):** Tasks 1-6 built and reviewed clean: T1 cf7be36, T2 750d900, T3 143d872+e623310
(fix: outlined buttons take taps across the row; tile helpers), T4 218450c+2a53698 (fix: Settle record button wraps), T5 a5015e1
(iOS sectionLabel 31 / microLabelFont 17; legacy nav-bar title attributes work live), T6 611ed03 (Android SectionLabel 42 / kicker 24).
Tallies: iOS 380, JVM 419. RUNES PROVEN on API 34 and 37.1 (debug proof). Ledger has every Minor (M1-M15 in .superpowers/sdd/e2/minors.md).
Founder said stop D-212's orphaned recorder (PID 94408); stopped.
**NEXT:** Task 7: Opus whole-branch review on 5f87586..611ed03 with minors.md -> ONE fix wave -> look (throwaway devices; cards/tiles/avatar
ring, title fold, Vardr screens, TalkBack/VoiceOver) -> sabotage -> full gate -> records -> merge.

## (previous) NOW: 2026-10-15 (session end): E2 SPEC APPROVED; NEXT = writing-plans for D-213 (static art), then SDD

**State:** main = D-212 (E1) merged 38480de + WORKLOG b80e34b + the E2 spec 29f2996, all pushed, no CI run. Nothing
deployed (0.5.0 rule). Tree clean.
**E2 spec APPROVED by the founder (popup, 2026-10-15):** `docs/superpowers/specs/2026-10-15-e2-theme-art-design.md`.
Founder decisions: ONE spec, TWO units (D-213 static art all three themes; D-214 motion); titles demo option A (face
everywhere via nav bar styling on iOS / `Type.display` on Android tab roots; Norse ornament only on the 4 tab roots + You;
demo https://claude.ai/artifact/Kv3JqDXUz13b2omN3FCwvi, source `.drafts/e2/titles-mock.html`); Vardr Labs main button
OUTLINED (second action plain text; Aurora/Norse filled); performance measured on sim/emulator in D-214, the founder's
phones at 0.5.0 (never a debug build on his own phone). Runes come from SYSTEM fonts (iOS Apple Symbols, Android Noto
Sans Runic; all 24 Elder Futhark + ᛫ verified in Apple Symbols iOS 26.5; checker `.drafts/d212/cmap.py`).
**NEXT, in order:** (1) superpowers:writing-plans -> `docs/superpowers/plans/2026-10-15-e2-static.md` for D-213 (the
spec's section 1 table + "Measured" paragraph; survey first: iOS `SkiptiTabBar.swift` (+ `RaisedAddStyle`),
`HomeShell.swift` (HomeTab, glyphs), `DesignTokens.swift` (`glassCard`, `PrimaryButtonStyle`, `SecondaryButtonStyle`,
`AuroraBackdrop`), `TypeSystem.swift` (`microLabelFont`), `GroupsScreen.swift` (`PictureTile`), the 47
`navigationTitle` sites and the 5 tab roots + You, ~20 `" · "` joins; Android `TabBar.kt`, `Tokens.kt` (`Type.kicker`),
`Aurora.kt` (`glassCard`, `auroraBackground`), `GroupScreens.kt` (`PrimaryAction`/`TonalAction`), `GroupsTab.kt`
(`PictureTile`), tab-root titles `Type.label(dark, 28)`, ~23 `" · "` joins). Values: Look Book CSS (`.t-norse`,
`.t-vardr` rules; plus/tab/card/tile) and `.drafts/d212/palette.py` for any new pair. (2) SDD on branch `e2-static`
(D-213), same process as D-212. (3) D-214 plan + SDD (branch `e2-motion`). Then E3, E4, F, H; the pre-0.5.0 security
audit; Developer Tools exemption reminder before any external build.
**How D-212 was run (reuse):** briefs = the plan's header sections (`## Global Constraints` .. before `### Task 1`)
PREPENDED to `task-brief` output (the script extracts only the task); per-task Sonnet implementer + Sonnet reviewer;
Opus whole-branch review, then fix waves (one fixer per wave) with re-review; look + re-look by Sonnet agents on
THROWAWAY devices (brief template `.superpowers/sdd/e1/look-brief.md`; never AFAF4221, EB725812-1E80-..., skipti-shots);
sabotage plan + runner (`.drafts/d212/sabotage-plan.md`, `.drafts/d212/sabotage.py`); full gate in the background;
`.drafts/common/record_unit.py` with a record dir (row.md must have 4 cells: Decision | Why | Where); merge --no-ff.
Every dispatch says: red tests fail never crash; wait on PIDs; stop your own Monitors/watchers before replying; explicit
paths; no attribution; no em dashes.
**Open for the founder (from D-212, unchanged):** judge Aurora's money pair, Norse labels at -2, Vardr uppercase names,
OutlinedButton borders on a device; the em-dash cleanup chip (task_16f91c00) after merge.

## (previous) NOW: 2026-10-15: E2 BRAINSTORM IN PROGRESS (D-212 merged before it)

**E2 decisions so far (founder, popups 2026-10-15):** (1) ONE spec, TWO units: D-213 static art for all three themes,
D-214 motion (the three living backgrounds, the heroes' art, the hex bolt's 30-degree turn, Reduce Motion, performance).
(2) Titles: face everywhere (navigation bar styling on iOS, in-content on Android), the Norse ornament (gold rule, rune,
silver rule) ONLY on the tab roots (Balance, Expenses, Groups, Settle up) and You; pushed pages face only (demo
https://claude.ai/artifact/Kv3JqDXUz13b2omN3FCwvi option A; source `.drafts/e2/titles-mock.html`). (3) Vardr Labs' main
button is OUTLINED (steel 1 px, Plex Mono uppercase, square), the second action plain steel text; Aurora and Norse stay
filled. (4) Performance: measured on simulator and emulator in D-214 (30 fps cap, still under Reduce Motion and Low Power);
the founder judges on his phones at the 0.5.0 internal build. NEVER a debug build on his own phone (same bundle id would
replace his real app and ledger).
**Facts found:** runes need NO bundled font: iOS 26.5 and 27 draw U+16A0.. via Apple Symbols (system), Android 34 and 37
ship NotoSansRunic-Regular.ttf (checked in the system images); the spec's condition for bundling is not met.
**Controller's calls:** notifications keep the middle dot (system-drawn); Norse tile initials stay Cormorant SC (the rune
font has no Latin); the Vardr dot grid is static (D-213).
**NEXT:** present the design sections (static, then motion) for approval, write the E2 spec
`docs/superpowers/specs/2026-10-15-e2-theme-art-design.md`, founder reviews it, then plan D-213.

## (previous) NOW: 2026-10-15 (early): D-212 (E1 themes) RECORDED AND MERGED, not deployed; NEXT = E2 theme art

**State:** D-212 merged into main (--no-ff) and pushed; nothing deployed (0.5.0 rule). Full gate 22/22 on 15c3c5e (6m49s;
cargo 298, iOS 355, JVM 396 fresh). Sabotage 49 rows: 43 exact, 5 broader, 0 NOT OK, 0 survived (3 closed by fix wave 4),
1 not run on purpose (`.drafts/d212/sabotage/table.md`). Look + re-look on throwaway devices with the local emulators
(`.superpowers/sdd/e1/look-report.md`, `relook-report.md`). BUILD-LOG: D-212 row, §3, §4 residue (a)-(k), changelog.
ADR-0041 (selectable themes); ADR-0036 amended (Night retired for now). Ledger "SDD ledger: E1" has every step.
**Founder decisions this session:** E1 spec approved (popup); screen titles take the theme's face IN E2 with the title
runes (popup, residue (b)).
**Tell the founder (open, his to judge on a device):** Aurora's calmer money pair and nudged accent; Norse labels at -2
readable but small; Vardr Labs uppercases names and notes shown as section labels (display only); the iOS glow was not
seen drifting (residue (c), E2 must prove motion); Android OutlinedButton borders now darker (textFaint); ~40
pre-existing em dashes in app copy (a follow-up chip was offered, run after this merge).
**NEXT (founder's order):** E2 theme art (Aurora curtains, Norse rune drift and rune details on tabs/ring/separators/
section labels/TITLES (face + ornament), notched corners, diamond tiles, the gold diamond +, Vardr frame marks, drifting
currency signs, the hex-bolt +, Reduce Motion, the rune font ask: check whether iOS draws runes first), then E3 (done
moments, haptics, the Haptics row), E4 (ring, settled meter, celebration, milestones), F, H, the later units; the
pre-0.5.0 security audit; Developer Tools exemption reminder before any external build.
**Process rules learned this session:** every dispatch tells agents to stop their own Monitors/watchers before replying
(memory `skipti-agents-stop-their-monitors`); plan briefs need the plan's shared sections prepended (task-brief extracts
only the task).

## (previous) NOW: 2026-10-14 (night): E1 / D-212 SDD IN PROGRESS on branch e1-themes; Tasks 1-6 approved, Task 7 running

**State:** branch `e1-themes` from main c459046. Ledger `.superpowers/sdd/progress.md` "SDD ledger: E1" is the recovery map
(every commit, every Minor and where it was routed). Commits: T1 b13a9d8 (iOS engine), T2 956791b (Android engine), T3 901e53e
(Cormorant SC + iOS faces), T4 ae51f08 (Android faces), T5 21bcb67 (iOS surfaces), T6 e3d919d (Android surfaces); T7 (the
Appearance page, both shells) dispatched on e3d919d. Each reviewed: Approved, 0 Critical/Important.
**Then Task 8 (controller):** Opus whole-branch review with the ledger's Minor list (incl. FIXED-24 radii on four cards both
shells: final fix wave), look on THROWAWAY devices only (check: Section headers sentence case, Vardr uppercase tab labels
truncation, Norse labels at text size -2, live theme switch on open screens, the hero in every set), sabotage matrix, full gate,
records (ADR-0041, ADR-0036 amendment, spec 18-sites correction, plan erratum .tracking -> .kerning, BUILD-LOG D-212), merge.
**Tell the founder:** under Vardr Labs a name or note shown as a section label is uppercase (display only, both shells);
~40 pre-existing em dashes in app copy (chip spawned, after merge).

## (previous) NOW: 2026-10-14 (evening): E1 SPEC APPROVED BY THE FOUNDER; PLAN WRITTEN; NEXT = SDD on branch e1-themes (D-212)

**State:** founder approved the E1 spec file (popup, 2026-10-14). Plan `docs/superpowers/plans/2026-10-14-e1-themes.md`
(8 tasks: 1 iOS engine + colour data, 2 Android twin, 3 Cormorant SC + iOS faces, 4 Android faces, 5 iOS surfaces,
6 Android surfaces, 7 Appearance page both shells, 8 controller: look, sabotage, gate, records, merge). Values derived
and measured by `.drafts/d212/palette.py` (0 floors failing; nudges: Aurora light accent #0C8A6C -> #0B7A5F, Vardr
light owing #0A6D93 -> #0A6C92, two Vardr light tone letters). Aurora money (decision 3): light owed #842D2A / owing
#356F2F, dark owed #EC7D79 / owing #97C87E (10-11 L* apart, hues clear of the accent green). Cormorant SC 4.000
SemiBold + Bold DOWNLOADED (founder's yes) to `.drafts/d212/fonts-download/` (sha256 648cd1c9.../78f70c79..., OFL, no
RFN). **Planning findings (record in D-212):** the spec's "18 iOS .font(.system) sites move to TypeRole" is wrong: all
18 are SF Symbols or emoji, they stay (correct the spec line in Task 8); iOS fonts must follow a theme switch live, so
TypeRole reads an @Observable `AppearanceState.shared` (the GroupPictureStore pattern) and scales from its content size
category; system controls take the theme accent through `window.tintColor`; Vardr kickers uppercase needs
`microLabelFont` (iOS, 45 sites) and `Type.kicker` (Android, 64 sites), each guarded by a source test.
**NEXT:** commit the plan on main, branch `e1-themes`, superpowers:subagent-driven-development (ledger section "SDD
ledger: E1" in `.superpowers/sdd/progress.md`, reports `.superpowers/sdd/e1/`). Then E2, E3, E4.

## (previous) NOW: 2026-10-14 (session end): D-211 merged; E1 SPEC WRITTEN (design approved); NEXT = founder reviews E1 spec, then plan

**State:** main = D-211 merged (325cc88) + the E1 spec. Nothing deployed. D-210 and D-211 cloud halves wait for 0.5.0.
**E1 (theme engine and Appearance), design APPROVED in popups 2026-10-14:**
`docs/superpowers/specs/2026-10-14-e1-themes-design.md`. Founder decisions: Night dropped for now (a stored `night`
reads as Dark; returns in a later version); Aurora keeps the Look Book green; Aurora money becomes a calmer red (debt)
and green (credit), distinct from the accent (Vardr keeps cyan/ember, Norse its pair); Cormorant SC may be DOWNLOADED
(OFL, github.com/google/fonts, inert font files, licence-checked, NOTICE) for Norse. The rune font (Noto Sans Runic)
is a separate ask in E2. The spec's "What exists today" section is the survey (tokens, AppearanceTests, fonts, storage).
**NEXT (founder's order):** founder reviews the E1 spec file -> superpowers:writing-plans -> SDD on a branch (D-212),
then E2 (theme art), E3 (done moments, haptics), E4 (ring, meter, celebration, milestones); then F welcome guide, H
"Just me" ledger, then the later units already decided: hide zero-net loops on People cards (D-210 decision), refuse
an invite for a place already held at creation (D-210 decision), Night (later version), Android `nextHlc` race.
**Before 0.5.0 deploys: a full security audit session** (founder, 2026-10-14): Opus subagents, starting from
`docs/CLOUD-SECURITY.md` §7 (the G-nn queue, including G-07/G-08: claims are a phone's word, now reaching profiles,
D-211 residue (b)), D-210 and D-211's "Before the 0.5.0 deploy" lists (read-only production checks, deploy order rules
-> Functions -> apps, §2 rows the same day), and the Developer Tools exemption reminder (CLAUDE.md §13: turn it off
before any external beta or store build). G-08's fix is a code unit built in that pass, before the deploy.
**Rules learned today (all in memory/CLAUDE.md/BUILD-LOG):** looks only on throwaway devices + local emulators (never
AFAF4221, EB725812, skipti-shots); red tests must fail, never crash the test host; agents wait on PIDs, never
`until ! pgrep -f`; an XCTest host starts no cloud work.

## (previous) NOW: 2026-10-14 (night): D-211 (D2) RECORDED AND MERGED, not deployed; NEXT = E1 theme engine (READ THIS FIRST)

**State:** D-211 merged into main (--no-ff) and pushed; nothing deployed (0.5.0 rule). Full gate 22/22 on 1976154
(cargo 298, iOS 341, JVM 379 carried forward from 23:45Z on the same tree, firebase 165, core lib 286). Sabotage 84
rows, 0 NOT OK, 0 SURVIVED (`.drafts/d211/sabotage/table.md`). Look S1-S8 WORK on throwaway devices
(`.superpowers/sdd/d2/look-report.md`). BUILD-LOG: D-211 row, §3, §4 residue, changelog, incident 26 (the production
devices). CLOUD-SECURITY: shareProfile + purge change pending at 0.5.0; G-08 widened.
**Before the 0.5.0 deploy (adds to D-210's list):** deploy `shareProfile` and the changed purge with D-210's callables
(rules unchanged); CLOUD-SECURITY §2 rows the same day.
**NEXT (founder's order):** E1 theme engine and Appearance (feedback-round spec "Units, in build order" 3: token sets
for three themes by two modes, the picker, text size, ADR-0041, AppearanceTests per pair; Aurora v2 the new default;
Look Book `.drafts/lookbook/skipti-lookbook.html` is the visual reference, approved v2.1). Process: brainstorming
(check what the spec and look book already decided first), plan, SDD. Then E2-E4, F, H.
**Looks, from now on:** throwaway sim/AVD + local emulators ONLY (never AFAF4221, EB725812, skipti-shots); hosted
tests on AFAF4221 are fine (an XCTest host starts no cloud work since D-211).

## (previous) NOW: 2026-10-14 (evening): D-211 (D2) BUILT on branch d2-profile, tasks 1-7 reviewed; final review running (READ THIS FIRST)

**State:** branch `d2-profile` from main 5df08c1. Plan `docs/superpowers/plans/2026-10-14-d2-profile-sync.md`, spec
`docs/superpowers/specs/2026-10-14-d2-profile-sync-design.md`. Tasks 1-7 built and approved (ledger
`.superpowers/sdd/progress.md` "SDD ledger: D2", reports `.superpowers/sdd/d2/task-N-report.md`). Tallies: core lib 280,
cargo 298, firebase 160, iOS 326, JVM 366. Final whole-branch review (Opus) dispatched on 5df08c1..efc2a37.
**NEXT:** final review fixes -> Task 8: look on THROWAWAY devices + local emulators only (two accounts: previews,
Rename absent, own name replaces typed, viewer with a photo; Android viewer bands), sabotage matrix (core, cloud, both
shells; the Task 4 fix already ran S1-S7 on iOS in `.drafts/d211/fix1/`), full gate, record D-211 (incl. the §5
incident below), merge --no-ff, push. NO deploy (0.5.0 rule). Then E1.
**INCIDENT (record in BUILD-LOG §5 with D-211):** the controller let agents launch the unreleased build on devices
signed in to PRODUCTION (`skipti-shots`; iOS 27 sim AFAF4221 = Test Vard). Verified read-only with gcloud: no records
written today (41 total, newest 2026-10-13T20:30Z); ONE write: Test Vard's `users/{uid}.name` at 19:25:45Z (the new
ensure-name step). Memory `skipti-looks-throwaway-devices` updated: looks never on those two devices.
**Also found today:** test hosts crash (macOS pop-ups) when red tests index empty arrays; agents' `until ! pgrep -f`
waits match themselves and never end (two killed). Both rules now in every dispatch.

## (previous) NOW: 2026-10-14 (afternoon): D-210 RECORDED AND MERGED (not deployed); D2 SPEC WRITTEN; NEXT = founder reviews spec, then plan (READ THIS FIRST)

**D2 (becomes D-211):** design approved in popups 2026-10-14 and written: `docs/superpowers/specs/2026-10-14-d2-profile-sync-design.md`
(7b12c38). Founder decisions: own phones full picture + enlarge, members a 120 px preview, no enlarge; old pictures stay in
private history (accepted, said in copy); server-bound `shareProfile` callable writes `MemberProfile` records marked
`profile: true`; a joined person's own `users/{uid}.name` replaces the typed name, follows later changes, Rename absent
for joined places. NEXT: founder reviews the spec file; then superpowers:writing-plans -> plan
`docs/superpowers/plans/2026-10-14-d2-profile-sync.md`, then SDD on branch `d2-profile`.

**State:** D-210 full gate 22/22 on b1a591c (8m03s; cargo 283, iOS 294, JVM 332 fresh, firebase 150), recorded in
BUILD-LOG (row, §3 with the 52-row sabotage table, §4 residue, changelog), CLOUD-SECURITY "Pending deploy at 0.5.0"
note + G-29 (inviteKnown outsider block) + G-30 (a removal's amounts are the remover's word), CLAUDE.md §3 map lines.
Merged `c3-roles` into main with --no-ff and pushed. NOTHING DEPLOYED (0.5.0 rule below still binding).

**NEXT: D2 Profile picture sync** (spec line 127: "the avatar becomes a personal record so it syncs like the name; core
change"). Survey done 2026-10-14 (facts): the avatar is vault kv `ledger/self-avatar` only (`Profile.swift:102`,
`Profile.kt:184`), JSON {none|emoji|photo jpeg b64}, 256 px JPEG 0.8, no byte cap, never leaves the phone, not in the
export; the UI says "not sent anywhere" (Profile.swift:231,440; Profile.kt:282,431), already half wrong (the name
syncs). The name syncs as personal GroupOp::You under 0x03 (`groups.rs:116`); `DeviceName`/`Security` are the
precedent for a new personal op (latest write wins; older builds list it as rejected, harmless; NEVER add a field to
`You`). Group pictures (D-187) are a group-scope GroupOp with photo hex <= 64,000 bytes (`check_photo`), NOT Cloud
Storage (storage.rules exists but Storage is undeployed, no client SDK). Design A (recommended, matches the spec): a
personal `Avatar { emoji?, photo? }` op reusing check_emoji/check_photo, shells step down to <= 64,000 bytes like
GroupPictures. Founder forks to ask: (1) do group members see your picture or only your own phones; (2) records are
immutable, so a removed or replaced photo stays in users/{uid}/records until account deletion: accept, or more.

## (previous) NOW: 2026-10-14 (session end): D-210 (C3) BUILT, REVIEWED, LOOKED AT; NEXT = full gate, record, merge

**State:** branch `c3-roles` (from main 27f6503), pushed, NOT merged. main untouched since 27f6503 apart from nothing.
Every C3 task is built on core, cloud, iOS and Android, reviewed (per task + Opus whole-branch review + re-review:
"Ready to merge: Yes"), and looked at on throwaway devices against the local emulators (iOS+iOS, Android owner +
iOS member, three rounds). Last tallies: core lib 271+ (cargo), Firebase emulator suite 147, iOS XCTest 294, JVM 332
(play, non-cached). **Sabotage matrix DONE (2026-10-14):** 49 rows (cloud 20, core 7, iOS 11, Android 11), every row
compiled and restored (cmp); 32 exactly-intended red, 15 intended + related reds (reasons in the table), 0 NOT OK.
Survivors (no missing test): cloud row 10 (acceptInvite joiner own-pid check is DEAD CODE: holderIn counts the joiner,
a second holder throws FAILED_PRECONDITION first; remove the dead branch or record it) and core row 23 (skrin
`personal = 0 AND` is an equivalent mutant: personal rows have NULL group_id). Gap: afterCommit rethrow tested only for
makeOwner and removeMember (add one test each for setAdmin, requestRemoval, declineRemoval, deleteGroup, or record).
Report `.superpowers/sdd/c3/sabotage-report.md`; runner and table `.drafts/d210/sabotage.py`, `.drafts/d210/sabotage/table.md`
(paste the table into BUILD-LOG §3). Baselines at the end: cloud 31 pure + 85 emulator, core 271, iOS 294, JVM 332.

**NEXT (in order, nothing else first):**
1. DONE 2026-10-14 (16711a1): acceptInvite's dead `own.uid !== uid` line deleted (the holderIn call stays: it refuses
   by throwing); afterCommit tests added for setAdmin, declineRemoval, deleteGroup (firebase 150), sabotage rows A-C each
   exactly-intended red (149/150), restored+cmp; `.drafts/d210/aftercommit/` (run.py, results.json, row logs).
   requestRemoval gets NO test: every uid it tells was read in its transaction, so no stored data can make a
   post-commit step throw without failing the transaction first -> §4 (no test-only fault hook in production code).
2. Full gate: `./scripts/gate.sh --full` in the background, machine free (no emulators: `pgrep -f qemu-system` empty;
   stop any `firebase emulators`). Bindings freshness compares against HEAD: bindings are committed, so it should pass.
3. Record D-210 in BUILD-LOG through `scripts/buildlog_edit.py` / `.drafts/common/record_unit.py`: the §2 row (problem,
   what was built, findings incl. the 2 pre-existing sync bugs and the pairwise doubling bug, sabotage matrix, artifact
   paths), §3 evidence verbatim, §4 residue (list below), changelog. `python3 scripts/check-buildlog.py`.
   Also: CLOUD-SECURITY.md "pending deploy at 0.5.0" notes (no §2 row until deployed); CLAUDE.md §3/§10 lines for the
   new files (Roles, RemovalFlow, GroupSettings, GroupJobs, groups.js, adoptRemovals) if needed.
4. Commit records, `git checkout main && git merge --no-ff c3-roles`, push, `gh run list --branch main --limit 3`
   (no run expected: `[skip ci]`).
5. Then the next unit in the founder's order (below). NO DEPLOY of anything (see decisions).

**Founder decisions (2026-10-13/14, binding):**
- C3 design: spec `docs/superpowers/specs/2026-10-13-feedback-round-design.md` "C3" + "Changed while planning";
  mock-up v2 https://claude.ai/artifact/GgYskSDAUMFMNvJoDGpM9x.
- **0.5.0 must include the Look Book revamp (E1-E4).** **Nothing is deployed (Functions, rules, apps) until everything
  for 0.5.0 is done**; units end at merge + records. D-210's cloud (rules + 8 changed/new callables incl. adoptRemovals,
  acceptInvite, purge) deploys WITH 0.5.0.
- Zero-net loops on People cards (after a simplified Settle all): HIDE them (core removes equal cycles from the pairwise
  view; totals unchanged): later unit.
- Invites for a place a member already holds: REFUSE AT CREATION (app never offers; cloud refuses createInvite,
  inviteUser, inviteKnown with a clear message): later unit.
- Build order after D-210: D2 picture sync, E1-E4 (Look Book: Aurora v2 replaces the purple palette, Norse, Vardr Labs;
  art; done moments; ring), F welcome, H Just me, then the open bug list.

**Before the 0.5.0 deploy (owed, with the founder's yes):** read-only production checks: every joined member has a used
invite (`invites` usedBy) and no (groupId, memberId) has 2+ distinct usedBy still in memberUids; Storage bucket/SA
permission check (purge and deleteGroup picture deletes are guarded now); deploy order rules -> Functions -> apps;
CLOUD-SECURITY §2 rows the same day; every tester must update before anyone removes someone with a balance (0.4.0 skips
type 6; the 0.5.0 build resets group cursors once).

**Found and fixed this unit (tell the founder in the D-210 summary):** pre-existing pairwise doubling of a settlement
larger than the debt (core); neither shell synced a newly added person at once (the invitee's app broke); the owner's
phone listened to a new group before the server had it and kept a dead listener (never heard members) since D-194/195.

**§4 residue to record (D-210):** leaving a group (no); no grace period on delete; notices (removed/deleted) accumulate in
the cloud, no age cleanup; adoptRemovals refused for good -> removal stays on that phone; Settle-first upload failure
not surfaced; deleted-notice-after-delete order untested; inviteKnown outsider participantId block (CLOUD-SECURITY G-nn);
purge leaves uid in other groups' former/askerUid; place claimed by two accounts stays refused; requestRemoval assumes
the create op is in the log; no concurrency tests for in-transaction reads; joiner whose pid is an unheld place;
rebuild per snapshot on main thread (measure); setForTest not #if DEBUG; pre-D-210 phone RemoveMember refused on fresh
devices; main-thread vault reads (Activity, ExpenseDetail); cards before roles load; Settle up absent on a push-opened
group page; People error text never cleared; no view tests; wireName grapheme vs surrogate asymmetry; GroupRoles.load
race; swallowed reads in ActivityFeed/sheet balance; review forgets its request across process death; Android removal
is a full-screen route; iOS delete has no holder/Back swallow (asymmetry); unseen Done bounces a screen; process death
mid Settle-first can append twice; waiting group has no way out; self-request detection compares names (later:
Function flag selfAsked); a re-added person gets "Name (2)"; adoptRemovals scope (sole member, no place check); type-6
blobs get no server meaning check; B4 reset costs one log re-read per group; Balance-line raw-id fallback.

**Not C3, tell the founder:** a member's Profile "Username" row stayed blank until set; group page pairwise line shows a
bare "+505"; zero-net cycle (decided: hide).

**Look/test tooling (reuse):** `.drafts/c3/a.sh` (adb tap/text/shot), `.drafts/c3/t.py` (tap Android element by text via
uiautomator), DEBUG `--emulator-as <sub>` with `--emulators` on both shells (iOS launch args; Android `--es args
"'--emulators --emulator-as prithvi'"`), emulators `cd firebase && FUNCTIONS_DISCOVERY_TIMEOUT=60 zsh -lic
'node_modules/.bin/firebase emulators:start --only auth,firestore,functions,storage --project skipti-26'`, then set
usernames via the setUsername Function with an unsigned token (see the ledger). Throwaway devices only; all deleted.
**Recovery map:** `.superpowers/sdd/progress.md` section "SDD ledger: C3" (every decision and commit), per-task reports
in `.superpowers/sdd/c3/`, screenshots `.drafts/c3/look/look-*.png`.

## (previous) NOW — 2026-10-13 (new session): C3 / D-210 BRAINSTORM IN PROGRESS (READ THIS FIRST)

Started C3 brainstorming with the founder (no device-check results for D-209 reported yet). Read so far: rules
`groups` match (`memberUids`, `createdBy`; records append-only, client-written), core `GroupOp::RemoveMember` (D-186,
presence register), hlutr's largest-remainder (ascending tie-break), purge's `groupsPlan`. Decisions as they land go
here, then into the feedback-round spec's C3 section.

**Founder's C3 answers (2026-10-13, popups):** (1) delete a group: OWNER only; the cloud copy is erased; every member's
phone keeps a READ-ONLY copy marked "Deleted by <owner>", which they can export or remove from their own phone.
(2) deleting with open balances: allowed, the confirm names what is still open; balances stay on the read-only copies.
(3) NO LEAVING: a member who wants out asks for their own removal through the request flow. (4) a removal request is
seen by the asker, the owner/admins AND the person named (who can e.g. settle up first). (5) the owner can hand over:
"Make owner", the old owner becomes an admin (also passes automatically at account deletion, as decided). (6)
redistribute: equal among the ticked members by default (largest remainder), with "Edit amounts" for custom shares
that must add up exactly before Confirm turns on.
**Claude's calls (record in the spec):** authority = cloud doc roles + Functions for membership/roles; the phones write
the ledger ops (RemoveMember, the redistribution) as they write expenses today (members are already trusted to write
records). "Settle first" = Settle up prefilled with the full balance, then the removal goes ahead. Owner cannot be
removed; admins remove members only; only account holders can be admins.
**Mock-up APPROVED v2** https://claude.ai/artifact/GgYskSDAUMFMNvJoDGpM9x (source `.drafts/c3/c3-roles-mock.html`). v1 ->
v2 (founder): Remove FIRST on a person's sheet, role actions below under "Role", each behind a confirm; ONE owner,
only the owner makes an owner; ADMINS CAN MAKE ADMINS; (Claude's call) only the owner takes admin away.
**Approach (founder):** the removal reaches the history through `removeMember` ATOMICALLY (Function writes the
phone-built records as type `removal`, moves uid to `former`); new core record 0x06 Redistribution (not payments).
**skipti-core 0.1.0 -> 0.2.0 in D-210** (founder). Design parts 1 and 2 approved; written to the feedback-round spec's
"C3 Roles, removal and deleting a group" section, committed 4453a03. Founder APPROVED the written spec and chose
SUBAGENTS (as C2). NOW: writing the plan `docs/superpowers/plans/2026-10-13-c3-roles-removal.md`; goldens derived
independently in `.drafts/c3/golden.py` (equal split: floor, leftover one each by ascending participant id, B's sign;
zero shares DROPPED from the record; shares same sign as B, sum exactly B).
PLAN WRITTEN: `docs/superpowers/plans/2026-10-13-c3-roles-removal.md`, 9 tasks (1-2 core, 3-4 cloud, 5-6 iOS, 7-8
Android, 9 prove/record/deploy). Planning changes recorded in the spec ("Changed while planning"): the mark is a FIELD
`removal: true`; requests Functions-only + notices; `groupRoles` callable; Settle first from core's plan; a new Group
settings screen. NEXT: subagent-driven-development, branch `c3-roles` from main, ledger `.superpowers/sdd/progress.md`
(start a new C3 section; C2's ledger stays above it).
EXECUTING: branch `c3-roles` from 27f6503; ledger section "SDD ledger: C3" in `.superpowers/sdd/progress.md` (RESUME from it after compaction; tasks marked complete are DONE). Tasks 1-2 (core) COMPLETE: core 0.2.0, Redistribution 0x06, export schema 3, forget_group (SkiptiVault), bindings regenerated (shells do not compile until Tasks 5/7). FOUND + FIXED a pre-existing pairwise doubling bug (0ea3e98). OPEN for the founder: zero-net cycle on People cards after a simplified Settle all (pre-existing). Tasks 3-4 (cloud) COMPLETE (132 emulator tests): four review rounds closed real holes (creator resolved as unjoined; forgeable claims -> places now bind to the SERVER-recorded used invite; outsider/insider participantId collisions; stale invites end on removal); acceptInvite now refuses a place held by another account. DEPLOY-TIME: read-only prod check (no place with 2+ current holders; every joined member has a used invite) needs the founder's yes; FOUNDER product call: refuse creating invites for held places? Task 5 (iOS sync) COMPLETE (238 iOS tests): reviews fixed a removed joiner's whole ledger breaking (self kept in its read-only group), redistributions now pass core's new ffi_merge_you_redistributions, notices dismissed only once applied; export no longer refuses a redistribution in a non-settlement currency (plan error). Task 6 (iOS screens) built and reviewed (Critical fixed: an admin offered owner-only decisions on a request naming an admin; Settle first could double-record payments; history named "Unknown member"). LOOK DONE on two throwaway sims (owner + member via the new DEBUG --emulator-as) against the local emulators: roles, request, redistribute (live math right), removal, read-only copy, group settings, delete all work; findings L1-L5 (delete confirm misses open balances; unjoined wording; request card balance line; "You deleted"; refresh on notice) in a fix wave. Not-C3 finding for the founder: a member's Profile username row stayed blank until set (the Home card DOES appear: earlier absence was timing). Fix wave done; RE-LOOK confirmed L1 (delete confirm names open balances) and L4 ("You deleted"). Task 6 COMPLETE (iOS 267 tests). Tasks 7-8 (Android) built, reviewed, fixed (GroupJobs holder for in-flight work; main-thread state). CROSS-PLATFORM LOOK (Android owner + iOS member) found two PRE-EXISTING bugs, both fixed: (1) neither shell synced a newly added person at once (the invitee's whole app broke with UnknownParticipant; now synced, and one unresolvable group no longer breaks every screen: "Waiting for this group to finish arriving."); (2) the owner's phone attached a new group's records listener before the server had the group, got PERMISSION_DENIED and kept the dead listener, so it never heard other members (since D-194/D-195; Android fixed + verified on device, iOS twin in progress). NEXT: Task 9 (final Opus review, sabotage, full gate, record, merge, deploy with the founder's yes).

## (previous) NOW — 2026-10-13 (night, session end): D-208 + D-209 shipped AND deployed; NEXT = founder's device check, then C3 (READ THIS FIRST)

**State:** main at the commit this lands in, pushed, no Actions run. Simulators/emulators off; throwaway devices deleted.
**Shipped tonight:** D-208 (joining an in-app invite: no false "already used"; the card leaves every phone; acceptInvite
deployed) and D-209 (usernames: required step at sign-in with "Later" only after a failed save, 3 suggestions, live
check, one change with a confirm, never removed, the blocklist; checkUsername + suggestUsernames created, setUsername
updated, deployed from df71a7a; the 3 live handles pass). Purge job's first run 21:29Z read: success.
**Founder's device check (needs new TestFlight/Play builds; version bump per CLAUDE.md §10.3 is his call):** a NEW
sign-in shows the step (name first if none; suggestions; available/taken/not available); a second phone of the same
account skips it; airplane mode during the step -> Save fails -> "Later" appears; You -> "1 change left" -> change ->
confirm -> "Changed once, it stays now"; an existing account without a username sees the card; iOS Home with enough
content to scroll: the large title collapses and re-tapping Balance scrolls to the top (D-209 residue (i)).
**Tell him (said in chat):** Cancel exists when the step is opened from the card or You; the empty Home shows two
filled buttons; delete-a-group request goes with C3.
**NEXT units, his order:** C3 roles and removal (+ delete a group), D2 picture sync, E1-E4 looks, F welcome, H Just me,
the 5 audit bugs + tonight's: a revoked session reads as signed in with "could not reach" (skipti-shots); new group
lands on Balance; Android lookup only from the keyboard key; iOS Groups glyph generic; his phone makes a link invite
(createInvite) before each username invite; the half-profile writers (D-209 residue (e)); purge logs nothing of its own.

**C3, START HERE (next session):** the decisions already made are in the feedback-round spec
(`docs/superpowers/specs/2026-10-13-feedback-round-design.md`, "C. People and usernames", the C3 bullet): the creator is
the OWNER; the owner names ADMINS; any member can REQUEST a removal, an owner or admin approves (and removes directly); a
person with a balance: the remover chooses SETTLE FIRST or REDISTRIBUTE among remaining members of their choosing (a new
core ledger op, every minor unit accounted for, integer money only, ADR-0002); ownership passes to the longest-standing
admin, else the longest-standing member, when the owner leaves or deletes their account; the removed person is notified
and keeps their own copy of the history; membership and roles change ONLY through Cloud Functions, each with refusal
tests (ADR-0040). ADDED by the founder 2026-10-13: **delete a group and its people** in the 0.5.0 UI (old groups stay
forever today). Existing code to read first: remove-person exists only for no-history, not-joined people (D-186, the
faint 18pt cross); `memberUids` on `groups/{id}` is the only membership today (no roles field); rules in
`firebase/firestore.rules` (`groups` match). Process: superpowers:brainstorming with the founder first (open details:
where roles show and are changed; the request/approve flow and its notifications; redistribute UI and the core op's
rounding rule; what "delete a group" does to other members' copies and to the cloud documents; what a removed person
sees), mock-ups as private Artifacts + approve popup (never the brainstorm server), then writing-plans, then
subagent-driven-development (the founder chose subagents for C2; ask again). Unit number: D-210.
**How C2 was run (reuse):** ledger `.superpowers/sdd/progress.md` (git-excluded); looks against the LOCAL emulators
(`cd firebase && node_modules/.bin/firebase emulators:start --only auth,firestore,functions --project skipti-26`, iOS
launch `--sync-emulator-proof` then `--emulators`, Android `--es args --sync-emulator-proof` / `--two-account-proof`
(proof-bo) then `--emulators`, `adb reverse tcp:9099/8180/5001`) on a THROWAWAY simulator (`xcrun simctl create`) and a
THROWAWAY AVD (`avdmanager create avd`), deleted after: the iOS 27 sim holds Test Vard's production session and
`skipti-shots` holds a revoked "Sam on Android" one; never wipe either. `applock.timeout off` via `simctl spawn defaults
write` on the throwaway only. Sabotage runner pattern: `.drafts/c2/sabotage.py` (cloud rows run pure + emulator suites;
phone rows run one test class; expected red SETS, not single tests).

## (previous) NOW — 2026-10-13 (night session): D-208 shipped and deployed; C2 in progress

**Purge job:** not run yet at 19:53Z (status.code -1); first run 21:29Z. Read it after, record in CLOUD-SECURITY (G-27).
**D-207 two-phone check:** lookup + Add and invite PASSED (founder). Recents one-tap invite: inviteKnown 200 from Test
Vard (iOS 27 sim, signed in by the founder) at 20:07:23Z, `inviteKnown: push {phones 2, delivered 2, failed 0}`;
arrived on his iPhone and LG. Screenshots `.drafts/d207-recents/`.
**BUG (joining an in-app invite, iOS; REPRODUCED on the sim 20:15Z):** one Join tap shows "This invite was already
used or has expired" although the join SUCCEEDED (acceptInvite 200, claim records written, group appears). Cause:
`Invites.accept` -> `CloudSync.syncSwitched()` -> `stopListening()` -> `IncomingInvites.stop()` empties `items`;
InvitationsList's `.sheet(item: $joining)` sits inside `if !invites.items.isEmpty`, so the presented JoinScreen is torn
down; the listener restarts, the inbox doc is still there (only JoinScreen's Done deletes it), `joining` survived, a
NEW JoinScreen's `.task` peeks the spent token -> 400 -> "already used" (peek 0.6 s after the accept, both times).
Second half: the inbox copy is deleted only by the phone that joined, so the account's OTHER phones keep a stale card
whose Join always fails (the LG, 20:09:55Z). Android's join is a route in AppRoot (state survives), so the tear-down is
iOS only; the stale card is both. Sim log `.drafts/d207-recents/sim-join.log`.
**Also found:** `skipti-shots` ("Sam on Android") has a REVOKED session (INVALID_REFRESH_TOKEN) yet looks signed in,
and every cloud call says "Could not reach SKIPTI's cloud": D-207's "headless emulator had NO network" was almost
certainly this (correct the record). Creating a group lands on Balance, not the group (both shells). Android username
lookup fires only from the keyboard's search key. iOS Groups tab's profile glyph is generic while Balance shows "TV".
Test data made: "Recents check" (Test Vard + Prithvi), "Invite test" (founder's, Test Vard joined), "Recents check A"
(Sam, local only).
**D-208 (founder: fix now, then C2) IN PROGRESS on branch `d208-join-invite`:** (1) cloud: acceptInvite deletes the
joiner's `users/{uid}/invites` copies of that token inside its transaction (functions test "joining from an in-app
invite clears it from your invitations, and only it": red first, then 19/19); (2) iOS: InvitationsList no longer owns
the sheet; `joinInvitationSheet` (People.swift) hangs it on GroupsScreen's / InvitationsScreen's ScrollView, `joining`
owned there (a first try kept an always-present empty VStack: it added a 14pt gap, seen and reverted); (3) both shells:
`Item.groupId` + `IncomingInvites.waiting(items, joined:)` hide invitations to groups already on the phone (twinned
tests: iOS AccountTests 36/36, JVM AccountTest 35/35). LOOKED (iOS 27 sim, Test Vard): stale "Invite test" card gone,
spacing unchanged; founder invited Test Vard to "Test Vard 2", Join -> "You are in Test Vard 2." (peek 200, accept 200,
NO second peek, 20:29:53-20:30:05Z). Screenshots `.drafts/d208/`. Android not looked at (no signed-in emulator).
Sabotage 3/3 BITES (cmp restored). Full gate 22/22 in 7m19s (cargo 243, iOS 205, JVM 223). RECORDED in BUILD-LOG
(row, §3, residue, changelog; D-207 §3 and residue (b) corrected in place: the emulator's session was revoked).
SHIPPED: 450fa3c, merged 5114b8f, pushed, no Actions run. acceptInvite DEPLOYED 20:45:53Z (founder's yes), read
back ACTIVE/skipti-functions/401, cloud-audit PASSED, CLOUD-SECURITY row. NEXT: purge job read after 21:29Z; C2.
**C2 BRAINSTORM, founder's decisions (2026-10-13 night):** username REQUIRED at sign-in, no skip (an account that
already has one, e.g. a second phone, skips the step); old handle RELEASED AT ONCE on the one change; never removable
(only changed); no name on the phone -> ask the name first on the same screen; availability LIVE as you type (he asked
about Redis at scale: answered, per account ~5-10 debounced checks in its life, a few dollars per million sign-ups,
Memorystore costs more just to exist; record in the spec); 3 suggestion chips from the name, cloud-built and checked;
a confirm step before the one change, You's row says "1 change left". OPEN (raised by me): GUARANTEES #2 forbids a
required screen at app open for existing accounts without a username -> card, not a cover (DECIDED: card).
Mock-up APPROVED v2 https://claude.ai/artifact/MBhJTr3LbzmgWg2EaFBxJU (source `.drafts/c2/username-mock.html`; "Suggested
for you" is the founder's wording). Spec section "C2 Usernames: design" committed 801b647. One list file
`firebase/functions/blocklist/usernames.json`, bundled by both apps (public repo: approved). BLOCKLIST REVISIONS
(founder, measured against /usr/share/dict/words, `.drafts/c2/parsed-list.json`): tier A terms that sit inside real
names or words move to whole-word (shit [Kshitij], randi, rundi, lund, lauda, nazi, paki, dalla, spic, tunne, rapist,
dengu, lynch, and the impersonation words admin, apple, support, official, security, staff, system, trust, police,
refund, billing, safety, verify, verified, moderator, administrator); skipti/vardr/vardrlabs stay anywhere; add
shitty, shithead, bullshit as anywhere; REMOVE "sunni" (a denomination). PLAN WRITTEN:
docs/superpowers/plans/2026-10-13-c2-usernames.md (6 tasks: cloud rule, callables, phone rule twinned, iOS UI, Android UI,
prove/record/deploy). Matching rule refined while planning: no letter-collapsing (it made "bobs" from "boobs" and blocked
bobsled); each term becomes a pattern whose letter runs may grow, never shrink (b+o{2,}b+s+). Reserved words (me, you,
help, team, root, null, undefined, everyone, mod, android, ios, upi, bank) match the WHOLE handle only, as today's
RESERVED did (parts would block me_and_you, team_rocket). Goldens by an independent Python reference
(.drafts/c2/golden.py): 23 allowed incl. kshitij/nazir/therapist/badminton/bobsled, 19 refused, scunthorpe the known
false refusal. List file drafted .drafts/c2/usernames.json. RACE found: setUsername's merge could make users/{uid}
before CloudProfile.register writes participantId -> setUsername refuses until the profile exists; the step registers
first. PURGE first run read 21:29Z: success, nothing logged of its own (CLOUD-SECURITY, 8973440 on the C2 branch).
C2 EXECUTING by subagents (founder's choice) on branch `c2-usernames`; ledger `.superpowers/sdd/progress.md` (RESUME block
there is newer than this). Tasks 1-5 done + reviewed; looks done on both shells against the emulators (throwaway sim/AVD,
deleted). Fixed during review: stale live checks enabling Save, a failed read hiding the card mid-entry (D-208 class),
the used change on another phone, a bounded post-sign-in wait, empty-Home card, Android state lost on recreation, iOS
change sheet presenting blank, chips truncating. Final review READY TO MERGE after two fix waves (founder decided: "Later" only after a failed save). Sabotage 19/19
(9 cloud, 5 iOS, 5 Kotlin; .drafts/c2/sabotage/). Full gate 22/22 5m22s (cargo 243, iOS 220, JVM 241). RECORDED D-209,
merged. NEXT: deploy setUsername/checkUsername/suggestUsernames (founder's yes; check the 3 live handles with
people.username AND people.search first; smoke checkUsername('sh1t'); callable count 11 -> 13; CLOUD-SECURITY rows +
G-nn enumeration doubt), then C3. Founder asked (2026-10-13): **delete a group and its people** in the
0.5.0 UI (old groups stay forever today) -> with C3. His phone also called createInvite at 20:14:38 and 20:29:18
before inviting by username: an unused link invite per add? (check).

## (previous) NOW — 2026-10-13 (session end, evening): D-204..D-207 shipped; feedback round underway; NEXT = C2

**State:** tree clean at the commit this block lands in; main pushed; no Actions run (all `[skip ci]`). Developer
Tools exemption ON (founder, 2026-10-13). Simulators and emulators shut down. Gates today ~5-6 min.

**Shipped today (BUILD-LOG rows + §3 evidence + §4 residue for each):**
- **D-204** push: `skipti-functions` lacked `roles/firebasecloudmessaging.admin` (held `firebasenotifications.admin`,
  the console composer); swapped with the founder's yes; every send logs `<fn>: push` {phones, delivered, failed,
  errors}. Proven on the TestFlight iPhone + Android (G-16 production check done).
- **D-205** re-tap a tab: back to the tab's first screen, again to the top (TabTap twinned).
- **D-206** You + Settings in five groups (Profile and account, Appearance, Security, Your data, About SKIPTI) from
  a tested `SettingsMap`; Home greeting ("Good evening, Name"); Android routes `account/*`.
- **D-207** adding a person: name + optional @username lookup with a preview card, Add and invite, recents from your
  other groups (`Recents`, twinned); new callable `inviteKnown` DEPLOYED (founder's yes; cloud-audit passes).
- Also: GitHub repo description corrected (it said end-to-end encrypted), founder's yes.

**Phase E results (founder):** E1-E4, E8 pass; Android invite links open the app; E9 untested; E5 fixed (D-204); E6/E7
were discoverability (search hidden under Invite) -> fixed by D-207.

**The feedback round (founder's 15 items, verbatim `.drafts/feedback-2026-10-13.md`):** spec
`docs/superpowers/specs/2026-10-13-feedback-round-design.md` (decomposition, every decision, approved designs). Plans
used: `docs/superpowers/plans/2026-10-13-{retap-tab,d1-you-and-settings,c1-adding-people}.md`.
- Approved artifacts: friction audit https://claude.ai/artifact/D69teLiNEycxDtjs7PdWLR (source
  `.drafts/g-audit/`); look book (themes, You, Settings, ring, done moments) https://claude.ai/artifact/EGKh2cQvJkT6d6wESv2QUk
  version 7 (source `.drafts/lookbook/skipti-lookbook.html`); add-person mock https://claude.ai/artifact/9rN75e4BCcew1GHWaWdFUQ.
- Item 15 blocklist APPROVED as drafted: `.drafts/username-blocklist-draft.md` (git-excluded; two tiers anywhere/whole
  word; normalise case, leetspeak, repeats, underscores/digits; cloud decides in people.js, phone mirrors; caste
  community names allowed; usernames only).
- Research notes `.drafts/feedback-research-2026-10-13.md`; flow map `.drafts/feedback-audit-map-2026-10-13.md`;
  vardrlabs.com design copies `.drafts/vardr-site/` (its CLAUDEwebdesign.md bans purple primaries/gradients).

**REMAINING, in the founder's order:**
1. **C2 usernames (next session, start here):** asked at sign-in (after Apple/Google succeeds; fine under
   GUARANTEES #5 since the person chose an account), suggestions from the profile name checked as AVAILABLE (a
   callable that checks a few candidates, rate-limited), set once and changed at most ONE more time (server counter on
   `users/{uid}`, enforced in `setUsername` with a refusal test), the approved blocklist in `people.js` beside RESERVED
   (two tiers + normalisation; tests with goldens; mirror on both phones for instant "not available"; never say which
   word matched). Check the 3 live usernames against it at deploy. Brainstorm C2's open details first (where the
   prompt sits in the sign-in flow on both shells; suggestion shapes), then plan, build, deploy with the founder's yes.
2. **C3 roles and removal:** owner (creator) + admins the owner names; any member REQUESTS removal, owner/admin
   approves (removes directly); a balance -> settle first or REDISTRIBUTE among chosen remaining members (new core
   ledger op, every minor unit accounted for); ownership passes to the longest admin else longest member; removed
   person notified, keeps their history. Functions + rules + refusal tests.
3. **D2** profile picture/emoji sync (item 8): the avatar is vault-only (`ledger/self-avatar`); make it a personal
   record like the name (core change).
4. **E1** theme engine + Appearance (3 themes x modes, text size, haptics switch, ADR-0041, AppearanceTests per pair;
   fonts BUNDLED, GUARANTEES #6; check iOS has a Runic font else bundle Noto Sans Runic, OFL). **E2** theme art
   (Aurora curtains, Norse runes everywhere + drifting runes, Vardr currency-sign hero + hex-bolt +; Lucide icon tiles
   on You's rows). **E3** done moments + haptics. **E4** completion ring, settled meter, celebration, milestones.
5. **F** welcome guide. **H** "Just me" ledger (decided; brainstorm first).
6. The 5 small bugs from the audit map (wrong "on Balance" copy, decimal comma, Profile "Saved" never resets, group
   page Recent delete has no Undo, raw Tax/Tip error).

**Open items for the founder:**
- Two-phone check of D-207 (a @username lookup + Add and invite; a recent's one-tap invite): never seen on a device.
- `okjPwA` production account from the emulator check: delete if a throwaway.
- `wBeDHd`'s iPhone has no fcmToken (no push for that phone): which phone is it?
- Android notification small icon is generic: wants SKIPTI's mark (he offered Affinity via MCP).
- Purge job's FIRST RUN is 2026-10-13 21:29Z: read `gcloud scheduler jobs describe
  firebase-schedule-purgeDeletedAccounts-us-central1 --location=us-central1 --project skipti-26` (lastAttemptTime,
  status) and the `purgedeletedaccounts` logs; record in CLOUD-SECURITY (G-27).
- Older, still open: Oracle archive deletion date; Play review test account; Firebase's unidentified SHA-256
  115335ac…; public-release items in CLOUD-SECURITY §7.1.

**Lessons recorded today (also in BUILD-LOG):** never launch on a simulator still booting (SpringBoard crash ->
wedged CoreSimulatorService; fix: quit Simulator.app, `killall -9 com.apple.CoreSimulator.CoreSimulatorService`, boot,
probe a launch); the headless `skipti-shots` emulator can come up with no network (`dumpsys connectivity`); zsh does
not word-split `$VAR` commands (use a function) and `=====` is a command expansion; macOS has no `timeout`; a fixture
where every self shares a name hid a missing rule (sabotage caught it).

## (previous) NOW — 2026-10-13 (new session, afternoon): Phase E results in; 3 failures being debugged

**Phase E (founder, 2026-10-13):** E1 sign-in, E2 two phones one account, E3 Your phones, E4 two accounts, E8
delete + keep: PASS. Android invite link after a Play install opens the app (assetlinks 57:F3 works). E9 (0.3.x
upgrade): not tested, stays unproven. **FAILED:** E5 push: no notification at all, either direction. E6: "search finds
nobody". E7: the founder found no contacts option anywhere.
**Purge job:** NOT RUN YET. Deployed 2026-10-12 21:25Z, "every 24 hours"; first run 2026-10-13 21:29Z
(scheduler `scheduleTime`; `status.code -1` = no attempt yet). Every 403 in its log is cloud-audit.sh's
unauthenticated probe (same second as the probes on every other function). Read it again after 21:29Z, then CLOUD-SECURITY.
**Findings so far (logs, read-only):**
- E6: `setUsername` 200 from iPhone (01:35Z, `Skipti/6`) and Android (04:47Z, motorola edge 70 fusion), but
  `findPeople` and `inviteUser` have NO call from either phone in 4 days (only probe 401s and deploy-time 400/404s).
  The search never reached the cloud. FindPersonScreen's code is right; its only entry is "Find X on SKIPTI" at the
  bottom of the Invite (QR) screen, and the contacts button lives only there. Hypothesis: the founder searched
  somewhere else (the add-person name field?). Asked him. Also: `People.failure` maps ANY NOT_FOUND refusal to "Nobody
  on SKIPTI matches" (People.swift:199), which would hide a missing function too.
- E5: onGroupRecord ran (200s at 04:48-04:54Z) and onDeviceCreated ran, but neither logs anything: four silent
  returns (no notice / no other member / no fcmToken on any device / FCM per-token errors are not thrown). FCM APIs
  are ENABLED (fcm, fcmregistrations, firebaseinstallations): the 2026-10-12 disable was Remote Config x2 + App
  Distribution only. A field-names-only Firestore read was refused by the classifier (production reads): asked the founder.
  Suspect: Push.saveToken swallows the error when the device doc does not exist yet and never retries.
**D-204 SHIPPED (this commit):** push never worked: `skipti-functions` held `roles/firebasenotifications.admin`
("Firebase Cloud Messaging Admin", the console composer), not `roles/firebasecloudmessaging.admin` (the HTTP v1 send).
IAM swapped (founder's yes); every send logs `<fn>: push` {phones, delivered, failed, errors}; deployed 16:59Z.
Proven: {phones 2, delivered 2, failed 0} 17:14:41Z, the founder saw it on the TestFlight iPhone and Android (G-16's
production check done). Gate 22/22 in 17m58s (after cargo clean): cargo 243, iOS 200, JVM 217. Residue in BUILD-LOG
§4 (wBeDHd's iPhone has no fcmToken; early returns unlogged; Android notification icon generic; okjPwA throwaway
account from the emulator check).
E6/E7 root cause: the @username went into the ADD-PERSON NAME box. FindPersonScreen and the contacts button are only
at the bottom of the Invite (QR) screen. Not code: feedback sub-project C.
**FEEDBACK ROUND (spec: docs/superpowers/specs/2026-10-13-feedback-round-design.md):** order approved: B (re-tap a tab,
APPROVED: `.id(rootToken)` rebuild + scroll-to-top, both shells) -> G (audit report; flow map in
`.drafts/feedback-audit-map-2026-10-13.md`) -> D+E mock-ups as private Artifacts (NOT the brainstorm server: plugin
code outside the repo, §13) -> C -> F. Research notes `.drafts/feedback-research-2026-10-13.md`. Found: remove-person
EXISTS (faint 18pt cross, only no-history, not-joined people, D-186; the joined refusal is relay-era); 5 small bugs
in the map. Spec committed (e9ac844).
**D-205 (B, re-tap a tab) SHIPPED (gate 22/22 4m49s; iOS 201, JVM 218, cargo 243; merged --no-ff):** plan
docs/superpowers/plans/2026-10-13-retap-tab.md. TabTap.decide twinned (iOS SkiptiTabBar.swift, Kotlin TabBar.kt), tests
BalanceLineTests.testATapOnTheShownTabGoesToItsRootThenItsTop + HomeTabsTest.a_tap_on_..., sabotage 3/3 (S1 iOS covered
ignored, S2 Kotlin same, S3 iOS selected test inverted; each exactly the new test red, cmp restored). iOS: per-tab
`.id(roots[tab])` rebuild + ShellChrome.scrollTop + `.shellScrollTop()` on the 4 roots' VStacks. Android already popped
to root on any tab tap (onSelect cleared openGroup); gains TabTap + rememberShellScrollState()/LocalScrollTop. LOOKED:
iOS 27 sim + skipti-shots, both cases, screenshots .drafts/d205/. FINDING: iOS large title stays collapsed after
scroll-to-top (ScrollViewReader and iOS 18 ScrollPosition.scrollTo(edge: .top) both tried: neither reaches the inset
top; reverted to the first version) -> §4. Android's title is content, so it returns.
**G (audit) PUBLISHED:** https://claude.ai/artifact/D69teLiNEycxDtjs7PdWLR (source .drafts/g-audit/friction-audit.html).
**D+E MOCK-UPS PUBLISHED, WAITING FOR THE FOUNDER'S APPROVAL:** https://claude.ai/artifact/EGKh2cQvJkT6d6wESv2QUk
(source .drafts/lookbook/skipti-lookbook.html): Home greeting, You (photo inside the completion ring; no-account ring
has no sign-in step, GUARANTEES #5), grouped Settings (Profile and account, Appearance, Security, Your data, About),
Appearance (3 themes, light/dark, SKIPTI-only text size, haptics), Security (danger rows apart), done moment (big
check + small bar). Decisions surfaced: Norse is dark-only; personal (no-group) expenses are his call.
**FOUNDER VERDICT on look book v1 (2026-10-13):** all three themes ship but ALL need work: Aurora = an ACTUAL
aurora borealis (the purple is "super generic"); Norse = rune-inspired elements through every screen (v1 had only the
+ and the hero), a DYNAMIC background with moving runes; Vardr Labs = "very stale": take vardrlabs.com's colour scheme
and design language (website repo on GitHub + the live site). Home "alright" but make it beautiful. Gamify: ALL FOUR
(completion ring, settled celebration, settled meter, group milestones). Personal expenses: ADD a "Just me" ledger.
**LOOK BOOK v2 PUBLISHED (same URL, version 2):** Aurora = green curtains with ray streaks + pink fringe over a
starry night (arctic dawn in light), accent aurora green, NO purple (his own web standards, Vardr_Labs_Website
CLAUDEwebdesign.md §1, ban purple primaries and purple gradients); Norse = runes with meaning on the tabs (Fehu
Balance, Gebo Expenses, Mannaz Groups, Tiwaz Settle up), Elder Futhark completion ring lit in gold, ᛫ separators,
notched cards, diamond tiles, drifting runes behind every screen, rune burst on settle; Vardr Labs = site tokens
(#0b0f13/#e8ecef, steel #9db0bf/#4c6172, cyan #5ad0f2, ember #e89055), point-cloud canvas hero, mono kickers .22em,
corner frame marks, square badges. New screens: group page (settled meter, 100-expenses milestone, celebration), Just
me ledger on Home. Site research saved .drafts/vardr-site/ (read-only copies).
Founder: v2 "significantly better"; fixed Vardr light hero (now light, steel/cyan cloud) and the + (machined hex bolt
head, reticle cross, turns 30 degrees): v2.1 = version 3, APPROVED 2026-10-13. Spec section "D and E" written
(themes, screens, ring steps, done moments, constraints incl. GUARANTEES #6 bundled fonts + iOS Runic font check,
units D1 You/Settings, D2 avatar sync, E1 theme engine + ADR-0041, E2 theme art, E3 done moments + haptics, E4 ring/
meter/celebration/milestones) and H (Just me, design open).
Founder then asked: Vardr Labs hero = CURRENCY SIGNS; a globe (v5) was rejected ("messing stuff up"); FINAL: signs tossed
across the card, each on its own slow smooth drift (look book version 6), then the rupee and majors pinned in clear view (version 7; spec updated). GitHub repo description changed with his yes (it said end-to-end encrypted): now "Local-first expense
splitting and personal ledger. Rust core, native iOS and Android, optional sync on Firebase. Never moves money."
**D-206 (D1) SHIPPED (gate 22/22 4m48s; iOS 203, JVM 221, cargo 243; merged --no-ff):** plan docs/superpowers/plans/2026-10-13-d1-you-and-settings.md.
SettingsMap/SettingItem/SettingsSection + Greeting twinned (iOS AccountHubScreen.swift, Kotlin AccountHubScreen.kt);
tests BalanceLineTests (2) + YouTest (3, incl. Back routes); sabotage 4/4 (S1/S2 phones leak without account, S3/S4
greeting 12h; each exactly its test, restored). iOS: AccountHubScreen = You hub (YouHero 104pt, deletion notice,
five rows with summaries, no icon tiles until E2), SettingsSectionScreen renders the map; HomeGreeting on Balance
(22pt). Android: routes account/profile|appearance|security|data, HomeRoutes.parent devices/delete-account ->
account/security, export/import -> account/data, signin -> account/profile; 3 pinned tests updated (intended);
AppearanceTest's D-176 order scan replaced by the five-group order + hero first + no filled accent. People.myUsername
on both. LOOKED: iOS 27 (greeting, You no account, Security no account rows), skipti-shots signed in (greeting,
You "Signed in with Google", Security with Your phones, Your phones -> Back -> Security). Screenshots .drafts/d206/.
**Item 15 word list APPROVED as drafted (founder, 2026-10-13):** `.drafts/username-blocklist-draft.md` (git-excluded;
two tiers: anywhere / whole word; normalisation incl. leetspeak and repeats; cloud decides, phone mirrors; caste
community names allowed; usernames only, not display names). It goes into sub-project C.
**C decided (founder):** C1 adding people -> C2 usernames -> C3 roles/removal. C3 = owner (creator) + admins the owner
names; any member REQUESTS removal, owner/admin approves (and removes directly); a balance -> settle first OR
redistribute among remaining members of the remover's choice (core ledger op); defaults: ownership passes to the
longest admin else longest member; the removed person is notified. Recents (founder): people from your other groups
when adding. C1 mock-up APPROVED: https://claude.ai/artifact/9rN75e4BCcew1GHWaWdFUQ.
**D-207 (C1) SHIPPED (gate 22/22 on the 3rd run, runs 1-2 were simulator boot/SpringBoard faults fixed by restarting CoreSimulatorService; iOS 204, JVM 222 carried forward, cargo 243; merged --no-ff):** plan docs/superpowers/plans/2026-10-13-c1-adding-people.md.
Recents twinned (People.swift/.kt) + tests; inviteKnown Function (participant id, both in the via group, never a
uid to the phone; deliverInvite shared with inviteUser) + 2 emulator tests (66/66); AddPersonSheet (iOS sheet) /
AddPersonScreen (Android full screen like InviteScreen); PeopleScreen's inline field replaced. Sabotage 3/3 (cloud
target-in-via check; recents self rule iOS + Kotlin; the FIRST self sabotage did NOT bite: every fixture self was "Me"
and the name rule hid it; fixture fixed with "Prithvi"). LOOKED: iOS no account (button, sheet without username, Ana
added + done bar; first try lost the typing to the keyboard race); Android signed in (username field; lookup showed the
offline message: the headless emulator had NO network, no request reached findPeople). Found/invite paths NOT seen on
a phone: founder's two-phone check. Simulator SpringBoard crashed once (launch during boot): Simulator.app quit.
DEPLOYED (founder's yes) 2026-10-13: inviteKnown from 092c3ec; read back (skipti-functions, ACTIVE, 401); cloud-audit.sh PASSED; CLOUD-SECURITY row.
NEXT (old line): C (brainstorm its remaining questions: add-person with @username preview, remove a joined person via a Function,
username at sign-in + suggestions + one change; then plan/build with the word list); purge job's first run 21:29Z: read
it and record in CLOUD-SECURITY; then C (word list to him
first) and F. Purge job's first run 21:29Z: read it after and record in CLOUD-SECURITY.
**Founder feedback (15 items, pasted 2026-10-13):** saved verbatim in `.drafts/feedback-2026-10-13.md`; triage
after the Phase E bugs. Items 5 (mock-ups) and 15 (word list) need his approval before building.

## (previous) NOW — 2026-10-13 (session end): 0.4.0 shipping; Firebase plan units 1-10 done; cloud audited

**State:** tree clean at the commit this block lands in. D-191..D-203 shipped and recorded (BUILD-LOG). 0.4.0 =
iOS 0.4.0 build 6, Android 0.4.0 / 4000. The founder has the TestFlight build validated/uploaded (dSYM warnings for
Firebase's binary frameworks are expected), filled Play's App content (privacy URL, Data safety: all optional, only
Contacts ephemeral; OAuth only; deletion URL skipti.vardrlabs.com/privacy#delete; review account in progress), moved
skipti.vardrlabs.com to Firebase Hosting, and TERMINATED Oracle. Live: `scripts/cloud-audit.sh` (default site the
domain) passes; assetlinks lists Play's CURRENT key 57:F3:A1:0F… + previous ED:82… + upload 41:84…; Google's
validator clean; Apple CDN has the new AASA. Cloud tidied: compute account leftover roles removed, 3 unused APIs off,
purge alert (G-27) created. gcloud installed (founder's yes), logged in as the founder, project skipti-26.
**Cleaned 2026-10-13:** `cargo clean` (target was 30 GB) and the stale ios/build/DD: the next gate rebuilds from
scratch (slower once). The Developer Tools exemption may be OFF (the founder turned it off to build): expect 30-40
min gates until he turns it back on; never turn it on yourself.
**NEXT (in order):**
1. Ask the founder for runbook Phase E results (docs/RELEASE-0.4.0.md): real Apple + Google sign-in on production,
   two phones, two accounts, push on TestFlight (production APNs), Your phones, delete + keep, 0.3.x upgrade (E9:
   unproven; shared groups may not re-share). Fix what they find as D-204+.
2. Read the purge job's first run: `gcloud scheduler jobs describe firebase-schedule-purgeDeletedAccounts-us-central1
   --location=us-central1` and the logs of `purgedeletedaccounts`; record in CLOUD-SECURITY.
3. Android invite link: after a reinstall from Play, does an invite link open the app (assetlinks now has 57:F3)?
4. Founder items: the Oracle archive's deletion date (~/SkiptiOracleFinal/oracle-final-20261013.tar.gz, 3.4 MB, 16
   backup + 20 vault entries); the Play review test account (Gmail, no 2SV, added as OAuth test user); Firebase's
   unidentified SHA-256 115335ac… (debug or post-quantum key?).
5. Comments only: GroupPictures.swift/.kt still say "sealed… relay sees only ciphertext".
6. Before any PUBLIC release: CLOUD-SECURITY §7.1 rows marked "public" (App Check, authorship/claims G-07/G-08,
   proof screens compiled out G-13, push caps/text G-17/G-18, keyed fingerprints G-22, G-26), API key restrictions
   (§8 step 9), the OAuth consent screen out of Testing mode, App Store privacy labels, the group wire format's
   relay-era fields (D-200 residue (a)).

## (previous) NOW — 2026-10-13: domain MOVED to Firebase Hosting, Oracle stopped; 0.4.0 shipping (READ THIS FIRST)

**Done by the founder (2026-10-13):** skipti.vardrlabs.com is a CNAME to skipti-26.web.app (Firebase custom domain);
`/version` 404 (relay gone); Oracle's IP answers nothing. Checked: `cloud-audit.sh` against the domain passes (now the
script's default site); Google's statements:list reads assetlinks with no errors; Apple's CDN holds the new AASA.
CLOUD-SECURITY §1/§2 and G-12 updated; runbook URLs now skipti.vardrlabs.com/privacy#delete.
**Resolved 2026-10-13:** A2: Play signs with 57:F3:A1:0F… (founder's Play install); assetlinks + README now list
current, previous (ED:82…) and upload; D-199 corrected in place; redeployed, validator shows three. Oracle TERMINATED;
Play URLs moved (G7). Archive ~/SkiptiOracleFinal/oracle-final-20261013.tar.gz (3.4 MB) lists 16 + 20 entries.
**Still open:** the archive's deletion date (founder); Firebase's other registered SHA-256 115335ac… is unidentified
(maybe the post-quantum key or the debug key: not added to assetlinks); the purge job's first run; Phase E results;
the repo's build caches are 34 GB (target 30 GB): offered `cargo clean`, founder to say.

## (previous) NOW — 2026-10-12: 0.4.0 READY; everything on Claude's side done; the founder ships (READ THIS FIRST)

**Shipped today:** D-198 phones + deletion (deployed), D-199 Hosting, D-200 crypto clean-up, D-201 audit, D-202
0.4.0 versions + release docs, D-203 privacy policy (live at https://skipti-26.web.app/privacy). Cloud changes on
the founder's "research and choose": compute account's leftover roles removed, 3 unused APIs disabled, the purge
alert created (G-27). gcloud installed (founder's yes), used read-only otherwise. `cloud-audit.sh` 29/29.
**The founder's, in order:** Developer Tools exemption OFF; Billing → Budgets look (unreadable without an API);
build, sign, upload 0.4.0 (notes in `.drafts/d202/`); Play Console App content: privacy URL, Data safety, account
deletion URL (answers in RELEASE-INTERNAL-TESTING §4); first test real Apple + Google sign-in on production; then
DEPLOY-ORACLE §11 (domain + Oracle) and switch the console URLs to skipti.vardrlabs.com.
**Runbook v2:** `docs/RELEASE-0.4.0.md` (Phases A-H; linked from RELEASE-INTERNAL-TESTING). Two findings while
writing it: (1) Oracle's live `/.well-known/assetlinks.json` answers 200 application/json with the body "…" (not
JSON): Android app links are broken on the domain today; Phase G (Hosting) fixes it. (2) Firebase's Android app lists
two SHA-256s (57f3a10f…, 115335ac…) that are NOT in the Hosting assetlinks or README (which say ED:82… is Play's key);
§5.3 says Play moved to a quantum-ready key on 2026-09-26. Runbook step A2 asks the founder for Play's current SHA-256s;
then update assetlinks.json + README (the hosting test ties them) + redeploy Hosting, and correct README in place if
ED:82 is not the current key.
**Fix later (comments only):** GroupPictures.swift/.kt still say the picture is "sealed" and "the relay sees only
ciphertext"; it is a plain group record in Firestore since D-192. Play's Data safety now declares Photos (D-187)
and User IDs; the privacy page mentions a group's picture (2026-10-13).
**Claude, next session:** read the purge job's first run (`gcloud scheduler jobs describe … --location=us-central1`,
logs of `purgedeletedaccounts`); after the founder's real-phone tests, act on what they find. Public-release items:
CLOUD-SECURITY §7.1 rows marked "public".

## (previous) NOW — 2026-10-12: console list READ via gcloud (CLOUD-SECURITY §7.1); founder deciding the fixes (READ THIS FIRST)

**Read (gcloud, read-only):** IAM clean except the compute account's leftover run.invoker + eventarc.eventReceiver
(safe to remove: triggers run as skipti-functions); Cloud Run invokers exactly right; org policy only the override;
sign-in Apple + Google only; Scheduler job enabled, not run yet; 3 unused Firebase APIs on (Remote Config x2, App
Distribution); budget unreadable without enabling an API; G-27 alert not created.
**Blocker found for Play:** READ_CONTACTS (D-197) needs Play's Data safety update and a privacy policy URL (D-197
residue (b), RELEASE-INTERNAL-TESTING §4): none is hosted. TestFlight internal is not blocked.
**Asked the founder:** which of the changes to make (purge alert, compute roles, disable 3 APIs) and whether to draft
a privacy policy page for Hosting (his text to approve: public).


**Shipped:** D-202 code e85e486, gate 22/22, recorded. Built products read 0.4.0 / 6 (iOS) and 4000 / 0.4.0 (APK).
**NEXT:** when `gcloud auth list` shows an account: `gcloud config set project skipti-26`, then READ-ONLY checks for
CLOUD-SECURITY §8 steps 1-7 (IAM policy; Cloud Run services' IAM; org policy on the project; enabled services; Auth
providers via identitytoolkit config; billing budgets; scheduler jobs), record results in CLOUD-SECURITY (§7.1 / §2),
ask before step 8 (creating the purge alert: a change). Then the founder: exemption OFF, build, sign, upload with
the notes in `.drafts/d202/`, then DEPLOY-ORACLE §11.


**D-202 in the tree:** iOS MARKETING_VERSION 0.4.0 / CURRENT_PROJECT_VERSION 6 (XC0011 + XC0012); Android versionName
0.4.0, versionCode 0*1_000_000 + 4*1_000 + 0 = 4000; RELEASE-INTERNAL-TESTING §5 sentence, §3 rewritten for 0.4.0, §4
(console list, domain move, TestFlight push), §1.3 and Android real-phone lists, §5.4 0.4.0 block. Notes drafted:
`.drafts/d202/testflight.txt` (737 chars) and `play.txt` (418 < 500).
**gcloud (founder's yes, 2026-10-12):** `brew install --cask gcloud-cli` (588.0.0, Google's archive via Homebrew's
checksum) at /opt/homebrew/bin/gcloud; the founder runs `gcloud auth login`; Claude uses it READ-ONLY for
CLOUD-SECURITY §8 steps 1-7 (step 8, the purge alert, is a change: ask first). Record the install in CLOUD-SECURITY §2
and in the §13 sense (a tool installed with the founder's yes).

## (previous) NOW — 2026-10-12: D-201 SHIPPED (audit); NEXT = unit 10, release 0.4.0; the founder's console list first (READ THIS FIRST)

**Shipped today:** D-198 (phones + deletion, deployed), D-199 (Hosting, live on skipti-26.web.app), D-200 (core's
relay crypto removed), D-201 (audit: code 33232be, gate 22/22, `scripts/cloud-audit.sh` 28/28).
**Waiting on the founder before 0.4.0 reaches testers:** CLOUD-SECURITY §8 console steps 1-8 (IAM, Cloud Run auth,
org policy, APIs, sign-in providers, budget, the Scheduler job, G-27's purge alert); a tap on
https://skipti-26.web.app/join#test (Copy link). At release: DEPLOY-ORACLE §11 (domain + Oracle shutdown), a
TestFlight push (G-16), the Developer Tools exemption OFF before signed builds.
**NEXT, unit 10 (D-202):** versions per CLAUDE.md §10.3 (iOS MARKETING_VERSION 0.4.0, CURRENT_PROJECT_VERSION 6 in
both XC0011/XC0012; Android versionName 0.4.0 → versionCode 4000 derived), RELEASE-INTERNAL-TESTING §5 sentence,
paste-ready TestFlight "What to Test" and Play notes (< 500 chars), gate, commit, push; then the founder builds,
signs and uploads (Claude never acts in the consoles).

## (previous) NOW — 2026-10-12: D-201 (pre-release security audit) WRITTEN, gating (READ THIS FIRST)

**Done:** `scripts/cloud-audit.sh` (read-only live checks; 28/28 pass; sabotaged twice, both caught); rules
re-released unchanged ("already up to date"); CLOUD-SECURITY §7.1 decides every open doubt (owner, before which
milestone), G-11 and G-14 struck as resolved, §8 gains the script and the founder's 9-step console list, §2 row.
**The founder's console list (§8 steps 1-8) and G-27's alert are needed before 0.4.0 reaches testers.**
NEXT: gate, commit, record D-201 (tally: no code change), push; then unit 10 release 0.4.0.

## (previous) NOW — 2026-10-12: D-200 SHIPPED; NEXT = the pre-release security audit pass, then 0.4.0 (READ THIS FIRST)

**D-200:** code b3ed73e (full gate on the committed tree 22/22 in 7m39s; the pre-commit run was 20/2: bindings
freshness as expected, and a real cargo fmt miss, fixed before the commit); sabotage 5/5; cargo 243 (was 286);
Cargo.lock −17 crates. Recorded (§2, §3, §4, changelog); D-192 residue (e) marked resolved in place.
**NEXT (the founder's order, 2026-10-12): the security audit pass**, starting from `docs/CLOUD-SECURITY.md` §7 (open
G-nn doubts, notably G-26 cooperative single-phone sign-out, G-27 nobody watches the purge) and §8 (re-verify the
live settings: rules deployed == repo, Functions' identity and roles, allUsers only on the eleven callables, Hosting
headers), plus THREAT_MODEL.md against what is built. Many §8 checks are console reads only the founder can do: hand
him the exact list. Then unit 10, release 0.4.0 (CLAUDE.md §10.3; iOS 0.4.0 build 6, Android 4000; notes; the
Developer Tools exemption OFF before signed builds; DEPLOY-ORACLE §11 with the founder).

## (previous) NOW — 2026-10-12: D-200 (pre-release clean-up: D-192 residue (e)) IN PROGRESS (READ THIS FIRST)

**Founder (2026-10-12): pre-release clean-up first**, then the security audit pass, then 0.4.0.
**Done in the tree (uncommitted):** `direct` gone from both About screens (Android `Licences.SHELL = "play"`
replaces `shell(packageName)`; both validators know only ios/play; tests: an unknown shell "web", and a "retired"
component with ["play","direct"] now refused on both). The bell: NOT dead (Android's push small icon
`ic_stat_bell.xml`, D-196), so NOTICE keeps it with corrected words (rewrapped), the XML's comment fixed, and a new
JVM test `the_notification_icon_is_lucides_bell_verbatim` (XML paths == TabIcons.PATHS["bell"]; the XML declared as
a Gradle input in build.gradle.kts). Android AboutTest 8 + HomeTabsTest 4 pass (17:45), iOS AboutTests 7 pass.
**In progress:** a read-only agent maps core's sync-only crypto (the 15 unused exports: device_agreement_generate,
device_safety_phrase, group_key_generate, invite_* (6), mnemonic_* (2), sync_key_wrap/unwrap, sync_seal/open) to
modules, deps, tests and docs; then remove, `make bindings`, gate, sabotage where a guard changes.
**Map back (agent, read-only; my decisions):** remove Tier A (15 exports, record types DeviceAgreementKeypair /
InvitePayloadIn / InviteOut, hex_decode_var, invite_err/secret) AND Tier B (lykill keeps ONLY random_bytes; aead.rs,
mnemonic.rs + lykill_bip39_english.txt, straumr/invite.rs deleted; straumr seal/open/pad/OpenError/GroupKeyState;
skrin save/load_group_state + device keys; tests/nonce_harness.rs; crypto_kats keeps ONLY the Ed25519 KAT) plus the
uncalled ffi_record_pack/unpack. Crates out: argon2, hkdf, aes-gcm, x25519-dalek (+ ed25519's rand_core feature if it
compiles). KEEP: ffi_sync_key_generate (iOS device id, LedgerStore.swift:316), random_bytes, ed25519 (fx/merchant
signature verify), sha2, zeroize, getrandom. NOT touched: schema_v1.sql tables (append-only), GroupOp::GroupKey /
ClaimDevice agreement key / maximum_security (reachable from exports the shells call; wire format: record as residue).
Rewrite: skrin's at-rest canary test (stores plaintext like the shells do now), fx_rate_set test signs with
ed25519_dalek::SigningKey, a_group_key_crosses_the_boundary uses a fixed key. Then make bindings, licences.py (drop
BIP-39 entry), NOTICE (BIP-39 para, stale e2e line), SBOM, docs (CLAUDE.md lykill line, README layout, SECURITY.md
envelope line, SPEC §3.1 as-built note, ADR-0040 add 0006), stale shell comments naming ffiSyncSeal.
**Done (uncommitted):** all of the above. cargo: clippy clean, tests 243 (was 286), wasm OK; Cargo.lock: 17 crates
removed, none added (argon2, hkdf, aes-gcm, x25519-dalek + transitives); bindings regenerated (-1166 lines);
licences.json regenerated (GROUP_ASSETS "Fonts and icons", twin tests updated); NOTICE (BIP-39 para gone, headline
no longer "end-to-end encrypted"); erase copy and Face ID string drop the Recovery Kit; nonce-session comments rewritten
(no sealing exists); KEPT ffi_record_pack/unpack (ADR-0034 header layout, tested; only wording). Sabotage 5/5
(`.drafts/d200/sab/results-final.json`): C1 SQLCipher key skipped (3 tests red: at-rest, wrong key, vault lifecycle),
C2 FX signature unchecked, K1 bell XML path, K2/I1 "direct" accepted. Runner fixes on the way (recorded): cargo's
"error: test failed" line matched my compile-error regex (now `error[E…]` only); I1 crashed twice at test start
because plain xcodebuild linked the OLD core with NEW bindings (UniFFI checksum abort) until `make ios-core`.
NEXT: full gate, commit (explicit paths; git rm'd files are staged: check `git diff --cached --stat`), record D-200.

## (previous) NOW — 2026-10-12: D-199 SHIPPED + Hosting LIVE on skipti-26.web.app; NEXT = unit 10, release 0.4.0 (READ THIS FIRST)

**D-199:** code 26bdcd0 (gate 22/22 in 3m01s; sabotage 7/7; emulator suite 62); Hosting live from it, headers and
bytes checked (CLOUD-SECURITY §1/§2/§8); preview channel deleted. README "Verifying the apps" published with the
founder's yes. The domain `skipti.vardrlabs.com` STILL points at Oracle (founder's decision: move with 0.4.0).
**NEXT, unit 10 = release 0.4.0** (CLAUDE.md §10.3): iOS MARKETING_VERSION 0.4.0, CURRENT_PROJECT_VERSION 6 (both
configs); Android versionName 0.4.0 → versionCode 4000 (derived); RELEASE-INTERNAL-TESTING §5 sentence; paste-ready
TestFlight "What to Test" + Play notes (< 500 chars); remind the founder to turn the Developer Tools exemption OFF
before the signed/store builds; then the founder runs DEPLOY-ORACLE §11 (custom domain, DNS, checks, backup, shutdown)
when 0.4.0 reaches testers. Before release also: core's sync-only crypto clean-up (D-192 residue e), the pre-release
security audit from CLOUD-SECURITY §7/§8 (G-26 cooperative sign-out, G-27 purge alert), the founder's real Apple/Google
sign-in + two-phone test on production, a phone tap on `https://skipti-26.web.app/join#test` (Copy link).

## (previous) NOW — 2026-10-12: D-199 (unit 9, Hosting + Oracle hand-off) IN PROGRESS (READ THIS FIRST)

**Founder's decisions (2026-10-12):** (1) the Android signing SHA-256s (Play app-signing `ED:82:…`, upload `41:84:…`,
both in private memory) ARE committed in `firebase/public/.well-known/assetlinks.json`, AND listed in README so people
can verify the app is his (show him the README text before pushing it; the old "SHAs never in git" rule stays for the
SHA-1s in google-services.json). (2) Cutover WITH 0.4.0 (unit 10): today Hosting is deployed and checked on
skipti-26.web.app only; the custom domain, the DNS change and Oracle's backup + shutdown are the founder's, at release.
**Plan:** `git mv deploy/join/index.html firebase/public/join/index.html` (ship-relay.sh + DEPLOY-ORACLE refs updated);
AASA applinks only (no webcredentials since D-192); assetlinks handle_all_urls only, one package; firebase.json
`hosting` (rewrite /join, CSP + no-referrer on /join paths, AASA content-type, own 404.html); emulator hosting port
5002 (5000 is AirPlay); `firebase/tests/hosting.test.mjs` (pure: CSP hash re-derived from the page, nothing
external, AASA vs iOS entitlement, assetlinks vs applicationId + manifest); DEPLOY-ORACLE "Retirement" section;
CLOUD-SECURITY rows; README fingerprints + firebase row.
**Progress:** all of the plan's files written; `hosting.test.mjs` 5/5; sabotage 7/7 (`.drafts/d199/`); Hosting
emulator (port 5002) and a one-day PREVIEW CHANNEL `https://skipti-26--d199-check-2wd2mpf4.web.app` (expires
2026-10-13 17:34) deployed (live site untouched). Finding: production MERGES matching header rules per key (later
wins), the emulator REPLACES the whole rule: both give one CSP per path; production also gives the JSON files the
catch-all CSP + no-referrer, plus Google's HSTS. The pane's Copy link falls back ("Copy the address bar instead") even
over https: clipboard permission in the pane, not CSP (no violation); the founder can tap the preview URL on a phone.
`firebase/.firebase/` (Hosting cache) gitignored. NEXT: full gate, commit, record D-199, `firebase deploy --only
hosting` (live, web.app only), CLOUD-SECURITY rows (preview + live, §1 Hosting, §8 checks), show README text, push.

## (previous) NOW — 2026-10-12: D-198 SHIPPED AND DEPLOYED; NEXT = unit 9 (Hosting + Oracle hand-off) (READ THIS FIRST)

**D-198:** code 50fb1f7, record fd9f89d (gate 22/22 in 4m54s; sabotage 19/19). **Deployed** to skipti-26 after the
founder's `firebase login --reauth`: `firebase deploy --only functions` (rules unchanged); the CLI enabled the Cloud
Scheduler API; 6 created, 8 updated; all 14 run as skipti-functions; the 4 new callables answer 401 without a token;
onDeviceCreated and purgeDeletedAccounts answer 403 (not public). Scheduler job not read back (no gcloud). Recorded in
CLOUD-SECURITY §1 (inventory, APIs), §2 (deploy row), §8 (eleven callables). Log `.drafts/d198/deploy.log`.
**Gate speed:** 4m54s, so the Developer Tools exemption reaches Claude 2.1.295's inner app; nothing for the founder.
**NEXT:** unit 9 = Firebase Hosting (invite links' web page) + the Oracle relay shutdown hand-off (founder runs it,
`docs/DEPLOY-ORACLE.md`); read the plan `docs/superpowers/plans/2026-10-01-firebase-backend.md` unit 9 first. Then
unit 10 (release 0.4.0; remind the founder to turn the Developer Tools exemption OFF before any store build).
**Open from D-198 (BUILD-LOG §4):** G-26 cooperative single-phone sign-out; G-27 purge alert before release; pending
card staleness; re-sign-in path proven by the look only; Android proof-screen mid-receive read.

## (previous) NOW — 2026-10-12: D-198 look and sabotage notes (kept; recorded in BUILD-LOG D-198)

**Sabotage 19/19** (`.drafts/d198/sabotage.py`, results `.drafts/d198/sab/results.json`): F1-F9 cloud (emulator leg),
I1-I5 iOS AccountTests, K1-K5 Android AccountTest; each red exactly as named, files restored byte-identical.
Since: CLOUD-SECURITY §2 IAM row corrected (said `deleteAccount`; the Function is `purgeDeletedAccounts`, and it acts on
accounts past their date, not the caller's uid); CLAUDE.md §10.1 gained the `.task` on an empty Group trap. Record
templates drafted in `.drafts/d198/final/` ({{GATE_TIME}}, {{CODE_COMMIT}}, {{EMULATOR}} are filled by hand).

**Look (iOS 27 sim + skipti-shots -gpu host, emulators auth/firestore/functions), all passed after the fixes:**
proof-sync converged (Android's proof line "groups here: From Android, received: 0" is a mid-receive read: it breaks
out when the expense lands, between applyRecords and rebuildProjection; the ordinary app showed both groups: record
as a §4 proof-screen quirk). iOS Your phones: two rows, This phone first, Sign it out -> Android "Signed out" notice,
"No account", ledger kept. Delete -> ONE alert "deleted on October 19, 2026", screen closes; Firestore deleteAt +7 d,
devices signedOut + signedOutAt, fcmToken gone; Android (Settings open) gets the true notice and drops to No account.
Re-sign-in remakes both device docs (no signedOut). Keep my account on iOS AND Android clears deleteAt +
deletionRequestedAt. Sign out everywhere on iOS: silent on iOS, notice on Android. Shots in .drafts/d198/
(i-phones, i-phones-signedout, i-delete, i-delete-danger, i-deletion-requested, i-pending, a-signedout,
a-everywhere, a-deleted-elsewhere, a-delete-danger, a-pending-dump.xml).
**Bugs found by the look, fixed (both shells unless said):**
1. False "signed out from another phone" notice after this phone's own delete / Sign out everywhere (listener raced
   the local sign-out). FIX RemoteSignOut.ownRequest + quietly{call + local sign-out} + arrived(signedIn, signOut).
2. iOS pending-deletion card never loaded: `.task` on `Group { if let date {...} }` never runs while the Group is
   empty (a Group hands modifiers to its children; it has none). FIX hub loads `Phones.pendingDeletion()` in reload(),
   card takes `@Binding date`. Add this trap to CLAUDE.md §10.1 at record time.
3. Settings open during a sign-out kept account controls (GUARANTEES #5; Android hub loaded once). FIX
   RemoteSignOut.signOuts counter (bumped in arrived); hubs reload on it; Phones/Delete screens leave on it (iOS
   dismiss, Android onBack when signed out). Delete flow now signs out through happened() (inside quietly).
4. "Delete my account" was accent-tonal; now destructive (SecondaryButtonStyle(destructive: true) / DangerAction).
Tests: iOS AccountTests 35 pass (testTheRemoteNoticeIsOnlyForAnotherPhonesRequest), Android AccountTest 34 pass
(the_remote_notice_is_only_for_another_phones_request), result 16:58. Everything shut down after the look.
**Known, not fixed (§4):** Android/iOS pending card does not refresh if ANOTHER phone keeps the account while
Settings is open (Keep is idempotent, harmless); "Last seen" updates only at launch/register.
**NEXT:** (2) sabotage `.drafts/d198/sabotage.py` from d197's pattern; rows: account.js groupsPlan/otherPhones/due,
store purge leave-vs-destroy, signOutDevice own-uid, signOutEverywhere revoke, iOS/Android signedOutSince + row
order, NEW: arrived() notice = !ownRequest (both shells), quietly resets flag, signOuts bump. (3) full gate (founder
to re-add Claude 2.1.295 inner app to Developer Tools first, else 30-40 min). (4) commit + record. (5) deploy (founder
runs `firebase login --reauth` first). Then unit 9, unit 10.

## (previous) NOW — 2026-10-03 (compaction point): D-198 (unit 8) CODE WRITTEN, UNCOMMITTED; NEXT = the look

**Shipped + deployed this session:** D-196 push (02a9a4f, c9b8bdd, deploy 6910646); Functions runtime SA fix
`skipti-functions` (cb74504); D-197 find people (8252c7f, cd195bc, deploy 5b1cab5). Founder granted Firebase
Authentication Admin to skipti-functions (recorded in CLOUD-SECURITY §2, uncommitted).
**D-198 in the tree, NOT committed, NOT gated, NOT deployed** (plan `.drafts/d198/plan.md`):
- firebase/: `functions/account.js` (pure), `functions/account-store.js` (purgeAccount/signOutPhones, db+auth injected),
  index.js +6 (onDeviceCreated, signOutDevice, signOutEverywhere, requestDeletion, cancelDeletion,
  purgeDeletedAccounts daily); `tests/account.test.mjs` (pure + store queried afterwards) + functions.test.mjs D-198
  block; `scripts/firebase-rules-test.sh` now `--only firestore,storage,functions,auth`. Emulator suite 57 PASS.
- iOS: `App/Phones.swift` (new; pbxproj A10151/B10163): PhonesCopy, Phones (rows/status/signedOutSince/authTimeMs/
  calls), RemoteSignOut, PhonesScreen, DeleteAccountScreen, DeletionPendingCard; CloudProfile.register returns
  `.signedOutRemotely` (stale flag older than auth_time -> delete+recreate doc); CloudSync phoneListener on own device
  doc; AccountHub: DeletionPendingCard + "Your phones" + "Delete account" links; Push.openedPhones (kind "device");
  SkiptiApp sheet + 2 alerts. AccountTests 34 PASS.
- Android: `Phones.kt` (new) twin; CloudProfile Outcome.SignedOutRemotely; CloudSync phoneListener; AccountHub
  onOpenPhones/onOpenDelete; AppRoot routes "devices", "delete-account", "delete-export" + RemoteSignOutNotices +
  openedPhones; Push.isDevice. AccountTest 33 PASS. Both apps BUILD (make android-app OK, manifest check OK).
- docs: SPEC §4.4 "As built (D-198)", CLOUD-SECURITY §2 row (written, not deployed) + §4.2 + §5 + G-25..G-27, WORKLOG.
**NEXT (in order):** (1) LOOK: start emulators (auth,firestore,functions; skipti-26) + AVD skipti-shots -gpu host;
fresh installs; `--sync-emulator-proof` on BOTH (same account proof-sync; was just verified: both device docs present);
relaunch both `--emulators`; iOS Settings -> Your phones (two rows, This phone first) -> Sign it out the Android ->
Android shows "Signed out" alert and is signed out (ledger kept); Delete account screen -> Delete my account -> alert
with date, Firestore users.deleteAt set, devices signedOut; re-sign-in via the proof then --emulators -> Settings
pending card -> Keep my account clears deleteAt. Screenshots to .drafts/d198/. Shut everything down after.
(2) Sabotage: copy `.drafts/d197/sabotage.py` pattern to `.drafts/d198/` (rows: account.js groupsPlan/otherPhones/due,
store purge (leave vs destroy), signOutDevice own-uid check, signOutEverywhere revoke, iOS/Android signedOutSince and
rows order). (3) Full gate (record_unit expects "22 passed"). (4) Commit code (explicit paths incl. new files
Phones.swift, Phones.kt, account.js, account-store.js, account.test.mjs) then record via
`.drafts/common/record_unit.py --unit D-198 --prev D-197` (templates to write in .drafts/d198/final like d197's).
(5) Deploy `firebase deploy --only firestore:rules,functions` (first scheduled function: Cloud Scheduler API may need
enabling/retry), verify 401s + functions:list --json serviceAccount, CLOUD-SECURITY §2 deploy row.
Then unit 9 (Hosting + Oracle hand-off), unit 10 (release 0.4.0; remind Developer Tools exemption OFF).

## NOW — 2026-10-02 evening: D-196 (unit 6, push + Recent activity) SHIPPED in code; DEPLOY WAITS ON THE FOUNDER (READ THIS FIRST)

**Shipped:** code `02a9a4f` + record commit (gate 22/22 3m36s on the tree; sabotage 14/14, I1/A1 green first ->
tests strengthened; iOS 189 executed, JVM 205, emulator leg 33 tests). Tree clean after the record commit; every
simulator/emulator OFF. What it does: author's phone writes `notice {text, expense}` on its own group expense records
(`PushNotice`, both shells); `onGroupRecord` (firebase/functions/index.js, logic in push.js) pushes to other members'
`devices.fcmToken`; `Push` (Push.swift / Push.kt) starts FCM only signed in + sync on, removes the token on sync off /
sign-out; auto-init OFF both shells; tap -> expense (`PushRoute`), behind the lock; Settings "Notifications" card;
Recent activity button on each group page. Android `CloudProfile.deviceId` now = records' id (Corrected D-196 in D-194).
**D-198 STARTED (unit 8: your phones + deleteAccount):** founder granted Firebase Authentication Admin to
skipti-functions (CLOUD-SECURITY §2). Plan `.drafts/d198/plan.md`. firebase/ DONE: account.js (pure) +
account-store.js (purgeAccount, signOutPhones; db/auth injected) + 6 Functions (onDeviceCreated, signOutDevice,
signOutEverywhere, requestDeletion, cancelDeletion, purgeDeletedAccounts daily); gate script now starts the Auth
emulator too; tests 57 pass (purge verified by querying the store). iOS DONE (Phones.swift A10151/B10163, AccountTests 34), Android DONE (Phones.kt, AccountTest 33), SPEC §4.4 as-built, CLOUD-SECURITY (G-25..G-27). NEXT: look (same account on both phones via --sync-emulator-proof; Your phones; sign the Android out from iOS; delete account; keep), sabotage, gate, record, deploy.
**D-197 (was IN PROGRESS, now shipped)** (plan `.drafts/d197/plan.md`): firebase/ DONE (people.js + setUsername/setFindable/findPeople/
inviteUser, rules users.name + limits, tests 49 pass, emailHash golden 6bdb7e96… = sha256("priya@example.com") by
Python). iOS DONE (People.swift A10150/B10162, AccountTests 31
pass), Android DONE (People.kt, AccountTest 30 pass, READ_CONTACTS + allowlist), SPEC permissions row, About privacy
line (hash 9218fdd5… via .drafts/d197/copy_golden.py), CLOUD-SECURITY (G-22..G-24). LOOKED (full loop on emulators: @bo_test, email
search, invite, Bo joins from Invitations, contacts prompt, username taken/reserved, findable off; 4 small UI fixes
re-looked). Sabotage 15/15 first run. SHIPPED: code `8252c7f` (gate 22/22 4m01s, iOS 196, JVM 212,
emulator 49) + record. DEPLOYED 2026-10-02 (8 Functions on skipti-functions, callables 401 unsigned; CLOUD-SECURITY §2). NEXT: unit 8 (your phones +
deleteAccount; deleteAccount needs the founder to add Firebase Authentication Admin to skipti-functions).
**FOUNDER DECISION 2026-10-02 (unit 7):** contacts = "Match all contacts" (permission + hashed emails to the cloud;
SPEC's permissions table, the Play allowlist (READ_CONTACTS), iOS NSContactsUsageDescription and privacy text change
with D-197). **FINDING 2026-10-02 (blocks real use):** the Functions' runtime identity (default compute SA) holds ONLY
roles/cloudbuild.builds.builder (new org: no automatic Editor) -> almost certainly no Firestore access and no FCM send in
production (never exercised: no signed-in prod call yet). FIXED same day: founder created SA
`skipti-functions` (Cloud Datastore User, FCM Admin, Eventarc Event Receiver, Cloud Run Invoker); index.js sets it; all
four redeployed and read back on it (`functions:list --json`); G-02 struck. NEXT: D-197 find people (contacts = match
all, founder 2026-10-02).
**DEPLOYED 2026-10-02:** founder re-logged the CLI; rules + Functions live; `onGroupRecord` created on the retry (Eventarc
agent propagation); callables still 401 without a token; CLOUD-SECURITY §2 rows added. STILL OPEN: real push proof on
the founder's phones (TestFlight/internal build); the App ID needs Push Notifications (Xcode adds it on his next signed
build); unit 7 starts with a FORK: SPEC's permissions table says "no contacts", design #13 says contacts matching
(Android READ_CONTACTS + Play user-data policy + uploading contact emails) -> ask the founder first.
**NEXT unit 7 = find people (D-197):** @username in Settings, exact email, contacts matching; `findPeople` +
`inviteUser` (push "Prithvi invited you to Goa trip" + users/{uid}/invites); findable switch default ON; rules for
usernames/{handle}. Then units 8 (phones + deleteAccount), 9 (Hosting + Oracle), 10 (release 0.4.0).

## NOW — 2026-10-02 (end of session): units 1-5 SHIPPED; APNs key uploaded; NEXT = unit 6 (push + Recent activity) (READ THIS FIRST)

**Where things stand (all pushed; tree clean; every simulator/emulator OFF):** Firebase plan units 1-5 = D-191..D-195
shipped and recorded; the invite Functions are LIVE on skipti-26 (401 without a token, 401 with a forged one); Firestore
rules live since D-193. Full gate = 22 legs (~5 min): the 21st runs `scripts/firebase-rules-test.sh` (Firestore+Storage+
Functions emulators, 26 tests: 20 rules + 6 Functions), the 22nd `npm audit` in firebase/functions. Recorder
`.drafts/common/record_unit.py` expects "22 passed". Latest code commits: 0171258 (D-194), 06ef682 + f82c58e (D-195),
70ec771 (CLOUD-SECURITY.md). **docs/CLOUD-SECURITY.md** = the cloud's security record (founder's ask): add a §2 row the
same day for ANY cloud/console/IAM/policy/deploy change; audit queue G-01..G-16.
**Founder did today:** IAM roles/cloudbuild.builds.builder on 403030719865-compute@developer; org policy Domain
restricted sharing overridden (Allow All) on project SKIPTI only; APNs key Key ID **WC967F2Y7S** (Sandbox & Production,
team scoped) uploaded to Firebase in BOTH dev and prod slots; removed the unpinned "firebase" Claude-app plugin.
**How the system works now (for unit 6+):** `CloudSync` (both shells) = the engine: pushAll (ownClaims first, ensureGroup
per new group, batches of 100 to users/{uid}/records or groups/{g}/records, kv sync/uploaded + sync/groups-up), listeners
(personal + groups array-contains uid + one per group, kv cursors sync/cursor/<g|personal>), receive (CloudRecords.refusal
filter for group logs, apply, receiveHlc, rebuildProjection, SyncStatus.changed). `CloudRecords` = record<->document.
`CloudProfile` = users/{uid} + devices/{Sync.deviceId}. `Invites` + `CloudFunctions` (callable protocol over HTTPS, no
Functions SDK). Proof args (debug only on Android: ProofArgs): --auth-emulator-proof, --sync-emulator-proof,
--two-account-proof [<link> on Android], --emulators (ordinary app vs local emulators; iOS #if DEBUG, DEBUG defined
since D-194). Emulators for a proof: `cd firebase && node_modules/.bin/firebase emulators:start --only
auth,firestore,functions --project skipti-26` (+ `adb reverse tcp:9099/8180/5001`). Deploy: `firebase deploy --only
functions|firestore:rules --project skipti-26 --non-interactive --force` from firebase/. A Function whose first create
failed never gets its public invoker: delete + recreate.
**NEXT, unit 6 = push + Recent activity (D-196):** FCM SDKs (iOS product FirebaseMessaging 12.19.2 via pbxproj like
FirebaseFirestore P10002; Android firebase-messaging from BoM 34.19.0) -> licences.py regen; register the FCM token in
devices/{id}.fcmToken (rules: add 'fcmToken' to devices hasOnly/changesOnly + tests); `onGroupRecord` Firestore-trigger
Function: push `notice` + group name to every OTHER member's devices, drop dead tokens; records gain `notice` (the push
line formatted by core on the author's phone, e.g. "Rahul added Dinner · ₹1,200"); permission asked at a meaningful moment
(first group / first join), not at launch; tap routes to the expense; iOS: Push Notifications capability/entitlement
(aps-environment) + UIBackgroundModes remote-notification?; Android POST_NOTIFICATIONS already in manifest.
Recent activity = ActivityScreen (kept since D-192, no entry point) on each group page. Push can't be fully proven on
the simulator: iOS 27 simulator supports remote pushes via simctl/APNs sandbox partially; real proof = founder's phones.
**Then:** unit 7 find people (@username in Settings, exact email, contacts matching; `findPeople` + `inviteUser` with
push; findable switch default ON; rules for usernames/{handle}); unit 8 your phones (device list, "Was this you?" push
onDeviceCreated, Sign it out / Sign out everywhere, `deleteAccount` = App Store requirement, residue: signedOut never read
yet, device doc not deleted at sign-out); unit 9 Firebase Hosting (/join page + apple-app-site-association + assetlinks
.json), DNS for skipti.vardrlabs.com (founder, Cloudflare), Oracle backup + shutdown commands; unit 10 release 0.4.0
(iOS 0.4.0 build 6; Android 0.4.0 / 4000; paste-ready TestFlight/Play notes; remind Developer Tools exemption OFF before
signed builds). Also before release: core's sync-only crypto clean-up (D-192 residue e), pre-release security audit from
docs/CLOUD-SECURITY.md §7 + §8, founder's real Apple/Google sign-in + two-phone test on production.
**Oracle:** still serves skipti.vardrlabs.com (/join page + app-link files) and the 0.3.x relay; DO NOT delete before unit
9 moves the domain to Firebase Hosting; then back up and shut down (founder runs the commands; Claude never SSHes).

## (previous) NOW — 2026-10-02: Functions LIVE; docs/CLOUD-SECURITY.md written; NEXT unit 6 needs the founder's APNs key (READ THIS FIRST)

**Functions live** (createInvite / peekInvite / acceptInvite, us-central1): after the founder's IAM grant
(roles/cloudbuild.builds.builder on 403030719865-compute@developer) and his project-only override of the org policy
Domain restricted sharing (Allow All), Claude deleted + recreated them; no token -> 401 UNAUTHENTICATED "Sign in first.";
forged alg:none token -> 401 UNAUTHENTICATED. D-195 residue (a) corrected in place.
**docs/CLOUD-SECURITY.md** (founder's ask, 2026-10-02): inventory, dated change log of every cloud change (who/why/what it
opens), controls (rules, Functions, identity, app-side), evidence, audit queue G-01..G-15, re-verification steps.
CLAUDE.md §10 rule + map line; memory `skipti-cloud-security-record`. Keep it current with every cloud change.
**NEXT:** unit 6 (push + Recent activity): founder uploads the APNs .p8 (Firebase -> Project settings -> Cloud Messaging ->
Apple app configuration, Key ID, team 27GK2T7UCT). Meanwhile units that need no founder step could be built
(7 find people, 8 phones + deletion — both Functions-backed, testable in the emulator).

## (previous) NOW — 2026-10-02: D-195 SHIPPED (invites; two-account test passes); BLOCKED on founder: Functions deploy IAM (READ THIS FIRST)

**UPDATE 2026-10-02 (after the founder's IAM grant):** the build now works (cleanup policy set, images > 1 day deleted),
but the callables answered 403 (Cloud Run invoker never set: the CLI sets `allUsers` run.invoker only on CREATE, and the
first create had failed at the build). index.js now declares `invoker: 'public'` (CALLABLE) on all three (emulator: 26
pass). Deleted + recreated the three functions: setIamPolicy refused, HTTP 400 "One or more users named in the policy do
not belong to a permitted customer, perhaps due to an organization policy" = vardrlabs.com org policy **Domain restricted
sharing** (iam.allowedPolicyMemberDomains; Google's secure-by-default for new orgs; its sibling
iam.automaticIamGrantsForDefaultServiceAccounts explains the build SA failure). The three functions EXIST but are not
invokable. **Founder step:** project-level override of that policy for SKIPTI only (Allow All), then Claude deletes +
recreates the functions (an update does not set the invoker) and checks they answer UNAUTHENTICATED without a token.

**D-195 shipped:** code `06ef682`, gate 22/22 in 5m08s, recorded; sabotage 9/9. Functions deploy to skipti-26 FAILED:
"Could not build the function due to a missing permission on the build service account" (Cloud Build, 3 builds; log
`.drafts/d195/deploy-fn.log`). APIs eventarc/run/pubsub/storage got enabled by the CLI. **Founder step (told in chat):**
Google Cloud console, project skipti-26 -> IAM & Admin -> IAM -> principal `403030719865-compute@developer.gserviceaccount.com`
(Default compute service account) -> Edit -> Add role "Cloud Build Service Account" (roles/cloudbuild.builds.builder) ->
Save. Then Claude: `firebase deploy --only functions --project skipti-26 --non-interactive --force` (--force sets the
Artifact Registry cleanup policy) and checks the three functions answer UNAUTHENTICATED without a token.
**NEXT after that:** unit 6 (push + Recent activity) needs the founder's APNs auth key (.p8) uploaded in the Firebase
console (Project settings -> Cloud Messaging -> Apple app configuration) and Messaging SDKs (FirebaseMessaging iOS
product, firebase-messaging Android). Units 7 (find people), 8 (phones + deletion), 9 (hosting), 10 (release) after.

## (previous) NOW — 2026-10-02: D-195 (Firebase unit 5: invites + the two-account test) BUILT, gating (READ THIS FIRST)

**Built + verified:** firebase/functions (plain JS ESM, Node 22; firebase-functions 7.4.0 + firebase-admin 14.4.0 pinned,
installed --ignore-scripts; protobufjs + @firebase/util postinstalls skipped; npm audit: 2 moderate (uuid via gaxios),
none high) with createInvite / peekInvite / acceptInvite (token = 32 random bytes base64url made on the server, only
sha256 stored, 7 days, single use, accept = transaction arrayUnion + usedBy). tests/functions.test.mjs (6) in the same
emulator leg (node --test --test-concurrency=1: files share the DB). Shells: Invites.swift / Invites.kt (InviteCopy,
InviteLink `https://skipti.vardrlabs.com/join#<token>`, CloudFunctions = callable protocol over HTTPS with the ID token,
NO Functions SDK; Invites.create/peek/accept; claim with a 64-zero key (core still wants one); QR from core; Invite/
JoinPaste/Join screens restored from git 0ed4d0e^ and rewired); People screen Invite section, Join a group buttons,
onOpenURL / Android intent + routes restored. SECURITY (kept from the relay, missing in D-194): CloudRecords.refusal = a
group's log brings in only that group's records (no other group's money, no "you"/personal ops, no forged this-device).
BUG FOUND BY LOOKING: Bo's People screen offered "Invite Al" (creator never claimed) -> CloudRecords.ownClaims (D-159's
ensureOwnClaims, ported) runs at the top of every push; fixed + seen. TWO-ACCOUNT TEST PASSED twice: iPhone = Al
(proof-al), Android = Bo (proof-bo, a different uid): Bo saw "Al invited you to Goa trip. You join as Bo.", joined,
got Al's Dinner, "I am Bo (You)", added Taxi + edited Dinner; Al's phone got both in 17 s; real Balance: Al "Bo owes you
$15.00", Bo "You owe Al $15.00"; Cy added on iPhone appeared on Android live; Invite Cy screen (QR) seen. Sabotage:
Functions 3/3 (`.drafts/d195/sabotage_fn.py`), app 6/6 (`sabotage_app.py`). Gate is now 22 legs (npm audit). NEXT:
commit, gate, record, push; then DEPLOY functions to skipti-26 (`firebase deploy --only functions`; may need
--force for the Artifact Registry cleanup policy); then unit 6 (push) needs the founder's APNs key.

## (previous) NOW — 2026-10-02: D-194 SHIPPED (your ledger syncs between your phones); NEXT unit 5 = invites + two-account test (READ THIS FIRST)

**D-194 shipped:** code `0171258`, full gate 21/21 in 4m42s, recorded (row, §3, residue, changelog). Sabotage 7/7.
Rules tests 20. Proof + live update seen (details in the D-194 block below and BUILD-LOG §3).
**NEXT: unit 5** — Cloud Functions (TypeScript, Node 22; `firebase/functions/` with pinned deps = the founder's §13 yes for
"Functions' npm deps"; `npm audit` joins the gate then) `createInvite` / `acceptInvite` (token hashed at rest, expiry;
accept adds caller to memberUids and links the named member), link + QR on both shells, the Join screens back, the
TWO-ACCOUNT emulator test (A creates, B joins, B adds, A sees; edit; delete). Needs the Functions emulator (Node, no
jar) and deploying Functions needs Blaze (on). Storage (group pictures) needs the founder to enable Cloud Storage.

## (previous) NOW — 2026-10-02: D-194 (Firebase unit 4, personal sync) IN PROGRESS (READ THIS FIRST)

Founder removed the unpinned "firebase" Claude-app plugin (2026-10-02); one npm process from before the removal lived on
in that app session (gone at the app's next restart). He said: go ahead with unit 4 "and the rest if it fits".
**D-194 design (decided, no fork):** no core change. The vault's records (RecordWire; expense 1, settlement 2, group op /
"you" 3, revision 5) ARE the sync unit. Personal scope (groupId nil) -> `users/{uid}/records/{millis-counter-device}`:
{type: "<n>", hlc{millis,counter,device}, authorUid, deviceId (= authorDeviceId), createdAt (server), payload{version,
epoch, flags, nonce: bytes, blob: bytes}}. Push = every personal record without kv `sync/up/<id>` (uploaded after the
server ack; "exists" = uploaded; records whose device id is not 32 hex stay local). Pull = snapshot listener
`createdAt >= cursor` (kv `sync/cursor/personal`, ms), applyRecord + receiveHlc, rebuildProjection, SyncStatus.changed().
Group-scoped records wait for unit 5. The second phone's "you" merges through D-154's records (no adoption needed; the
profile's participantId is just the first phone's). CORRECTION to D-193: the phone's cloud id becomes the records'
existing device id `sync/device-id` (D-193 minted a second one, `device/id`); one id per phone.

**D-194 progress (built, verified, not committed):** CloudSync engine both shells (push: batches of 100, groups created
first via `ensureGroup` (member/notMember/offline), records to users/{uid}/records or groups/{g}/records, kv
`sync/uploaded` + `sync/groups-up`; pull: personal listener + `groups where memberUids array-contains uid` + one records
listener per group from kv `sync/cursor/<g|personal>`; receive = batch apply + receiveHlc + rebuildProjection +
SyncStatus.changed). Twin tests iOS AccountTests 12, Android AccountTest 11 (+ ProofArgs). Rules test +1 (20): the apps'
exact documents (bytes payload) accepted, a re-upload refused. PROOF `--sync-emulator-proof` both shells at once: each
made a group + expense, each saw the other's within 0-4 s, "payer reads as you: true" both (D-154 merge works across
phones: D-193's finding resolved without adoption). LIVE: ordinary iPhone app (`--emulators`) showed 4 then 5 groups,
the 5th arriving while open with no touch. FINDINGS: (1) iOS project never defined DEBUG -> every `#if DEBUG` was false;
added SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG to XC0001 Debug (found when `--emulators` silently hit production:
permission denied from prod, no emulator connection in its log). (2) SECURITY: Android's exported MainActivity honoured
any `args` extra in RELEASE builds: proof launches skip the app lock and some read the vault (`--list-vault`), and the
emulator proofs sign the account out -> another app could open the ledger past the lock. Fixed: `ProofArgs.proof`
returns null unless debuggable (A4 sabotage). Pre-existing since the proof args (D-163 era lock skip). (3) One phone id:
CloudProfile.deviceId = Sync.deviceId (`sync/device-id`); D-193's `device/id` dropped. Sabotage 7/7
(`.drafts/d194/sabotage_app.py`). Plan re-cut recorded in the plan file. NEXT: commit, gate, record, push.

## (previous) NOW — 2026-10-02: D-193 SHIPPED (Firebase unit 3); NEXT unit 4 = personal sync (READ THIS FIRST)

**D-193 shipped:** code `ca130dd`, full gate 21/21 in 5m04s (the new leg "firebase rules (emulators: every refusal
tested)", 5 s), recorded (row, §3, residue, changelog). iOS 172 tests, JVM 187 (185 + 2), cargo 286, rules 19.
Sabotage 12/12 (rules F1-F8, app S1/S2/A1/A2). **Firestore rules + indexes DEPLOYED to skipti-26** after the gate
(storage rules NOT deployed: Cloud Storage not enabled yet; founder console step in unit 5).
Recorder `.drafts/common/record_unit.py` now expects 21 legs.
**NEXT: unit 4 (personal sync)** — notes in `.drafts/d194/notes.md` (vault already has put_record/all_records of
RecordWire; likely no core change; outbox + listener on users/{uid}/records; adopt the account's "you" (D-193 finding:
a second phone's self id differs from users/{uid}.participantId); two-phone convergence test against the emulator).
Write the unit's task list into the plan file first (plan says "units 2-10 get their own detailed task lists when they start").
**Founder still to be told:** the unpinned "firebase" Claude-app plugin (see the D-192 block below); D-192 + D-193 shipped.

## (previous) NOW — 2026-10-02: D-192 SHIPPED; D-193 (Firebase unit 3: rules + emulator gate leg + profile/device docs) IN PROGRESS (READ THIS FIRST)

**D-192 shipped:** code `27cb6a2` (deletions landed early in `0ed4d0e`, incident 25), full gate 20/20 in 4m33s on 27cb6a2,
recorded (row, §3, residue, changelog, §5 incident 25; CLAUDE.md §9 now says 25). iOS 170 tests, JVM 185, cargo 286.
**Founder yes (2026-10-02, AskUserQuestion):** the pinned CLI may download and run the Firestore emulator 1.22.0 and the
Storage rules runtime 1.1.3 jars (size + SHA-256 pinned in firebase-tools 15.31.0) — §13 exception for these two only.
**D-193 plan:** drafts in `.drafts/d193/firebase/` (firestore.rules for the WHOLE data model: users (owner only, keys
participantId/createdAt/updatedAt), devices (owner; keys name/platform/appVersion/lastSeen; signedOut only by Functions),
users/records + groups/records (immutable, authorUid == caller, createdAt == request.time), users/invites (Functions write,
owner reads/deletes), groups (members read; create with memberUids == [me]; members change only name/emoji),
usernames + invites = no client access; storage.rules: groups/{g}/picture.jpg, members only, jpeg <= 64000 bytes;
tests/rules.test.mjs = node:test + fetch against the emulators' REST, NO new npm dependency). Then: gate leg (before the
iOS leg; recorder's PASSED -> 21), firebase.json (rules paths, storage emulator 9199), Firestore SDK on both shells
(iOS product FirebaseFirestore 12.19.2: links abseil/grpc-binary/leveldb/nanopb already in Package.resolved; Android
firebase-firestore from BoM 34.19.0) writing users/{uid} + devices/{id} at sign-in and at start; licences regenerated;
deploy firestore rules to skipti-26 after the tests pass (storage rules wait for the founder enabling Storage, unit 5).
Plan's unit 2 "sync-only core crypto deleted" was NOT done in D-192 (residue (e)); a later clean-up unit.
**D-193 progress:** firebase/{firestore.rules,storage.rules,firestore.indexes.json,tests/rules.test.mjs} in place, firebase.json
(rules + storage emulator 9199); 19 rules tests PASS in the emulators (jars verified against the pinned SHA-256);
`scripts/firebase-rules-test.sh` + gate leg "firebase rules (emulators: every refusal tested)" before the iOS leg (gate is
now 21 legs: update CLAUDE.md §4 and `.drafts/common/record_unit.py` PASSED); firebase/ added to gate_inputs.py's excluded
dirs; `.gitignore` firebase/*-debug.log. Rules sabotage 8/8 (`.drafts/d193/sabotage_rules.py`, results_rules.json).
DONE since: Firestore SDK on both shells (iOS product FirebaseFirestore P10002/A10146; Android firebase-firestore from the
BoM, Firestore 26.6.0), `CloudProfile.{swift,kt}` (users/{uid} create-or-touch, devices/{id} create-or-refresh; device id =
core's 32-hex `ffiNewParticipantId` in vault key `device/id`; `Cloud.db` with MEMORY cache only), CloudSync.registerPhone
(after sign-in and on app active, once per launch per uid), Account.signOut -> accountSignedOut. Twin tests (iOS AccountTests
+2, Android AccountTest +2: field lists == the rules' own lists read from firebase/firestore.rules; device id made once, 32
hex) PASS. Licences: 255 components (Android grpc/guava/protobuf-javalite/re2j (Go = BSD-3, text fetched from re2j-1.6)/
checker-qual (MIT, jar's text)/animal-sniffer (MIT, text from tag 1.23); iOS Abseil, gRPC, BoringSSL (text from
google/boringssl@b8b3e6e, gRPC v1.69.0's submodule), LevelDB, nanopb); About tests pass both shells. Manifest check OK (no
new permission). Analytics NOT linked (Frameworks: FirebaseFirestoreInternal, absl, grpc, grpcpp, openssl_grpc; the one
"[FirebaseAnalytics]" string is FirebaseCore's log category). PROOF seen (emulators auth+firestore, --project skipti-26,
local only): iOS + Android same uid nNamEp…, profile keys createdAt/participantId/updatedAt, devices "iPhone, ios" and
"Android SDK built for arm64, android", signedOut write refused; 0 FATAL. FINDING: second phone's "you" != profile's
participantId (first phone's kept; the rules forbid changing it) -> unit 4 must adopt the account's "you" (D-154 merge).
iOS proof first said otherAccount: the gate simulator's vault held D-191's emulator uid (app reinstalled on AFAF only).
NEXT: app sabotage (S1/S2/A1/A2), CLAUDE.md §4 (21 legs, new leg) + map, record_unit PASSED=21, full gate, record,
deploy firestore rules to skipti-26.
**Founder still to be told:** the unpinned "firebase" Claude-app plugin (see the D-192 block below).

## (previous) NOW — 2026-10-01 late: D-192 (Firebase unit 2, removals + guarantees) BUILT, UNCOMMITTED

**INCIDENT (record as BUILD-LOG §5 incident 25 in the D-192 record):** commit `0ed4d0e` ("WORKLOG: D-192 built…") staged only
WORKLOG by path, but the index already held the `git rm` deletions, so it swept in 98 deleted files (iOS/Android relay files,
tests, `server/`) under a WORKLOG message; pushed. main at 0ed4d0e does not build until the D-192 code commit lands. Not
rewritten (no force-push). Lesson: `git diff --cached --stat` before EVERY commit, even a docs-only one; `git rm` stages.
**State of the tree (remaining 61 changes, NOT committed; do not lose them):** D-192 = removals + guarantees, all code done and
verified, waiting only for the full gate + BUILD-LOG record + commit. Untracked new files to stage:
`ios/Skipti/App/CloudSync.swift`, `android/app/src/main/java/com/vardrlabs/skipti/CloudSync.kt`,
`docs/adr/0040-firebase-replaces-the-relay.md`. Deletions are already staged by `git rm` (iOS: 21 app files listed in
`.drafts/d192/ios-del.txt` + 13 test files; Android: 17 files in `.drafts/d192/android-del.txt` + 15 tests incl.
IdentityTest; `server/` crate). Stage modified files by explicit path (`git status --porcelain | grep '^ M'` lists them;
CLAUDE.md, Cargo.toml, Cargo.lock, GUARANTEES.md, README.md, SECURITY.md, THREAT_MODEL.md, docs/SPEC.md, the shells'
files, both manifests/plists, native/licences/licences.json? (regenerated, check `git status`), docs/WORKLOG.md).
**What D-192 did:** iOS + Android: deleted relay sync (LedgerSync, GroupSync, SyncService/SyncStatus engine, IdentityService,
RelayTransport/RelayConfig, PasskeyAuthService/PasskeyCeremony (Apple's `run` moved into AppleSignIn.swift), DeviceKeyService,
DeviceApprovalScreen, AuthorizeDeviceProof, SyncProof, DeviceNames, Kit* (Recovery Kit), OnboardingView/Screen (kit),
BackgroundCheck, AccountDeletion (delete account returns in unit 8), InviteJoin (invites return in unit 5), SyncPrompts),
the bell (ActivityBell/Badge; ActivityScreen kept for unit 6 "Recent activity"), Join-a-group buttons (Balance/Groups/
Overview/GroupPicker; unit 5 restores), People screen invite/in-app/UNVERIFIED sections, Settings cards (waiting approval,
devices, Max security, notifications, kit, delete account), relay proof args. NEW seam `CloudSync` (both shells):
`ledgerChanged()` (iOS `CloudSync.shared.ledgerChanged()`, Android `CloudSync.ledgerChanged(vaultPath)`), `appIsActive`,
`syncEnabled/setSyncEnabled` (vault key `sync/enabled`), plus slim `SyncStatus` (revision, changed(), syncNow no-op) and
`HexCodec`. Android `u64BE/u64FromBE` moved into LedgerStore companion; launch flag `LAUNCHED_KEY="app/launched"` in
AppRoot. `accountHubModel(signedIn:syncOn:)` (no authority). Info.plist: BGTask + UIBackgroundModes fetch removed;
entitlements: webcredentials removed (applinks kept). Android manifest: CheckJobService removed, POST_NOTIFICATIONS kept
(unit 6). `server/` deleted, Cargo workspace = core only (lockfile: 0 added, 139 removed, verified). Docs: GUARANTEES #5/#6/#7
rewritten + 2026-10-01 amendment; ADR-0040; THREAT_MODEL rewritten for Firebase; README; SECURITY scope; SPEC superseded-in-
part note; CLAUDE.md (§1, map, guarantees, rules, direct-flavour lines, NEW SwiftUI trap: never EmptyView() in
.safeAreaInset). About copy (promises #5-#7 + privacy) changed on both shells; golden SHA-256
28d5ba3417c374c9e8cdab5e4ae7c61475962a88998afed3f4c10086ce770e0e derived in `.drafts/d192/about_golden.py`.
**FOUND by looking (iOS):** banner slot `EmptyView()` inside `.safeAreaInset` made EVERY tab's content vanish (titles+bar
stayed); fixed to `Color.clear.frame(height: 0)` (HomeShell.swift `banner`). Record as a §3 finding (not a sabotage row).
**Verified:** iOS `make ios-app` 170 tests 0 skipped 0 failures (was 295/11 skipped; removed tests were relay ones) — run
BEFORE the banner fix; banner fix built + seen on simulator (Balance content back, Settings clean). Android `make
android-app` OK (manifest check OK) + `make android-test` 185 tests x1 variant, 0 failed; seen on API 34 (Balance, Groups
"New group" full width, no bell, 0 FATAL). Licences regenerated: 228 components (`--check` current). Sabotage 4/4
(`.drafts/d192/sabotage.out`; rows S1 iOS accountHub no-account controls, S2 iOS About copy "not end-to-end", A1/A2 Android
twins; runner `.drafts/d192/sabotage_d192.py`, logs `.drafts/d192/S1.log` etc.; each ran only the 2 affected classes).
Simulators/emulators all OFF.
**NEXT (exact):** 1) `make ios-app` again is NOT needed separately — run the FULL gate on the committed tree: commit code
first (explicit paths, `[skip ci]`, message "D-192: …"), then `date -u +%Y-%m-%dT%H:%M:%SZ > .drafts/d192/gate.start;
./scripts/gate.sh --full > .drafts/d192/gate.log` in background. 2) Record with `.drafts/common/record_unit.py --unit D-192
--prev D-191 --dir .drafts/d192/final ...` — FIRST write `.drafts/d192/final/{row.md (4 columns: | D-192 | decision | why |
where |), section3.md, residue.md, changelog.md}` (pattern: `.drafts/d191/final/`), and
`.drafts/d192/results_d192.json` (rows S1,S2,A1,A2 with failed lists like d191's JSON; recorder variants now play-only).
Residue: Join buttons/invites gone until unit 5; delete account gone until unit 8 (App Store requires it before release);
Recent activity unwired until unit 6; empty-state copy still says "join one you were invited to"; Android
`GroupListScreen(onJoin)` unused param; About.kt `shell(".direct")` + known shells still list "direct"; lucide bell drawing
still shipped (NOTICE unchanged); POST_NOTIFICATIONS kept for unit 6; relay still RUNNING on Oracle until unit 9; core
sync-only crypto (lykill mnemonic, envelopes, group keys, invite crypto, FFI exports) still in core — a later cleanup unit.
3) Push, WORKLOG. Then unit 3 (rules + emulator gate leg + users/devices docs) per the plan.
**Founder items:** told him a Claude-app plugin "firebase" (author Google) runs `npx -y firebase-tools@latest mcp` each
session (found at ~/Library/Application Support/Claude/local-agent-mode-sessions/.../rpm/plugin_017vZWi6gTxaeueU83GKzU4d);
NOT yet told — mention in the next reply: it is unpinned latest code under the Developer Tools exemption; suggest he disable
it (we use the pinned CLI). He asked to stop the iOS simulator when idle (memory saved). Testers will uninstall 0.3.x.
$300 GCP credit noted (not useful for speed). Spec/plan: docs/superpowers/{specs,plans}/2026-10-01-firebase-*.md.

## (previous) NOW — 2026-10-01: FIREBASE REVAMP — D-191 (unit 1, sign-in) SHIPPED; NEXT unit 2 removals

Founder (2026-10-01), after testing 0.3.1: groups do not sync, and an expense a friend added does not show up; device list
still empty. DECIDED by him: scrap the relay sync (cloud) and replace it with Firebase; local ledgers stay as they are;
real push notifications (APNs/FCM) instead of D-189/D-190's bell + background check; the guarantees change accordingly.
He asked for the Firebase console steps and for questions before any build. State: brainstorming (superpowers), questions
put to him via AskUserQuestion; the design doc goes to docs/superpowers/specs/2026-10-01-firebase-backend-design.md once
approved, then a plan. Root cause of the sync failure NOT established (production relay is 2160d90 = latest server code,
so it is not an undeployed relay). Uncommitted ios/Skipti.xcodeproj/project.pbxproj + ios/Skipti/Info.plist = Xcode
re-saving them during his archive (same entries reordered, comments dropped); left alone, do not commit by accident.
No Firebase MCP in the connector registry; the official one is firebase-tools (npm) = needs his yes under CLAUDE.md §13.
**Round 1 answers (2026-10-01):** privacy = MEMBERS ONLY BY SECURITY RULES (plain Firestore data; no E2EE; #7 rewritten
honestly); personal ledger ALSO syncs via Firebase (own account only); push = CLOUD FUNCTIONS (Blaze plan; Oracle relay
retired); Firestore region = nam5 (US multi-region). Console steps already handed to him in chat (Firebase on the existing
GCP project, Analytics OFF, iOS+Android apps, Auth Google+Apple, Firestore, APNs .p8, Blaze + budget alert).
**Round 2 answers:** GROUPS NEED AN ACCOUNT (personal ledger works with none; #5 changes; my default: groups already on a
phone are never deleted, stay usable and upload at first sign-in); push text = who/what/how much ("Rahul added Dinner ·
₹1,200 in Goa trip", tap opens it); REMOVE the E2EE extras (Recovery Kit, safety phrase/UNVERIFIED, Maximum security +
approvals, passkeys + email code) and "make most use of Firebase" (optional "new phone signed in — was this you?" popup);
card on Blaze as fallback, design to stay in free tier. Direct flavour: he delegated ("deep research, choose what's best,
we have to trust Google") -> MY DECISION: retire `direct` (ADR-0010's purpose was SMS capture/F-Droid; F-Droid refuses
proprietary Firebase; FCM + Google sign-in need Play services; SMS never built). Node v26.4.0 at /opt/homebrew; no
firebase CLI. Join page + app-link files are served by Oracle nginx (/var/www/skipti/join via deploy/ship-relay.sh).
**Round 3 answers:** KEEP ACTIVITY (as "Recent activity" on each group page), DROP the bell AND the background check;
Firebase CLI = YES, pinned in the repo (devDependency + lockfile, npx; he runs `firebase login`; Functions' npm deps pinned;
npm audit joins the gate) — this is his §13 yes for firebase-tools + functions deps only; Oracle RETIRED, skipti.vardrlabs.com
-> Firebase Hosting (/join + app-link files; he changes DNS in Cloudflare; backup before shutdown); new phone sign-in = push
"Pixel 8 signed in to your account — was this you?" + Your phones list with remote Sign out (Cloud Function revokes).
**Round 4:** APPROACH A approved ("Go with A and let's build it"): core's immutable records as Firestore docs, vault stays
the screens' database, outbox up + snapshot listeners down. NEW ASK: invite existing SKIPTI users directly with a push;
find people by ALL THREE: @username, exact email, contacts matching; findable by default with a switch.
SPEC WRITTEN: docs/superpowers/specs/2026-10-01-firebase-backend-design.md (decisions table, data model, rules, Functions,
units 1-10, founder console actions). NEXT: founder reviews the spec; then the plan (superpowers:writing-plans) in
docs/superpowers/plans/, then unit 1 (needs his console steps 1-4 + the two config files).
**Spec APPROVED ("Looks good"). Release = 0.4.0 both (iOS 0.4.0 build 6; Android 0.4.0 / 4000).** He wants the console
steps to END with connecting Firebase to Claude's MCP so Claude does the rest. DONE: firebase/package.json pins
firebase-tools 15.31.0 (8 days old; 15.32.1 was 1 day old), installed --ignore-scripts, lockfile committed; npm audit:
9 (4 moderate, 5 high) all in the CLI's own transitive deps (basic-ftp, uuid, @opentelemetry/core), dev tool only, not
shipped — recorded, not force-fixed. .mcp.json (repo root) runs firebase/node_modules/.bin/firebase mcp --dir firebase;
initialize handshake verified. Steps handed to him: Firebase on existing GCP project (Analytics off, accept terms), Blaze +
$5 budget, Auth Google + Apple enabled, Play app-signing SHA-1/256 pasted in chat (keep out of repo), `firebase login`,
restart session + approve the "firebase" MCP. Then Claude: apps, Firestore nam5, SHA, config files, plan, unit 1.
**Firebase setup DONE by CLI (2026-10-01):** project `skipti-26` (GCP 403030719865, the old OAuth clients' project);
Auth Google + Apple enabled by the founder; apps registered: iOS 1:403030719865:ios:26a313b36c35afcdcd43a8, Android
1:403030719865:android:b8e41683ad5cacfacd43a8; 4 SHAs (Play app-signing + debug, SHA-1 + SHA-256; values in private memory
only); configs fetched to ios/Skipti/GoogleService-Info.plist + android/app/google-services.json, BOTH GITIGNORED (the json
holds the SHA-1s); IS_ANALYTICS_ENABLED false. The Firebase MCP HUNG 24 min on its first call (my fault for retrying
rather than checking): .mcp.json removed; use the pinned CLI with --non-interactive. BLOCKED: Firestore API not enabled
(403) -> founder creates the database in the console (nam5, production). NEXT: then the plan, then unit 1.
**Firestore created by the founder: (default), STANDARD, FIRESTORE_NATIVE, nam5 (read back).** He asked CLI vs MCP: answered
CLI (tokens, reliability). PLAN WRITTEN: docs/superpowers/plans/2026-10-01-firebase-backend.md (removals moved to unit 2;
unit 1 detailed: SDKs iOS 12.19.2 / BoM 34.19.0, FirebaseConfig in code, Account, sign-in rewired, has-account checks).
STARTING UNIT 1 = D-191, Task 1.1. Drafts in .drafts/d191/.
**D-191 (Firebase unit 1) CODE COMMITTED 4d99a33** — Firebase sign-in both shells (Account + FirebaseConfig in code; iOS
SDK 12.19.2 SwiftPM, FirebaseAuth only; Android BoM 34.19.0 + firebase-auth), sign-in pages new copy (no email/passkey/
kit step), has-account reads -> Account (iOS 6, Android 4; relay-session USERS left dormant for unit 2), Android
passkeys deleted, direct flavour RETIRED (src/direct, testDirect, Makefile, licences.py), reCAPTCHA: ACCESS_NETWORK_STATE
allowlisted + READ_GSERVICES removed, licences.py: iPhone group + BSD-3 (protobuf text in native/licences/) + Google
terms + javax.inject override + BoM-managed deps parsed (it was silently skipping firebase-auth). FOUND: Android launch
crash (Compose state in Kotlin objects created off-main: ActivityFeed/GroupPictures/SyncStatus) — fixed via
createState() in onCreate; 0.3.1 carries the race (tell founder). Auth emulator proof: same uid both shells.
Sabotage 9/9 (S1-S4 iOS, A1-A3 Android, C1 crash by hand, L1 licences). Drafts .drafts/d191/final; results_d191.json.
**D-191 SHIPPED:** code 4d99a33 (gate 18/20: About tests on the new inventory) + fix 4e01834 (gate 20/20 12m49s) +
record commit. JVM is now ONE variant (play): 301 tests; the "305x2" said mid-unit counted a stale testDirect folder
(Makefile + recorder fixed, folder deleted). Founder to know: (1) 0.3.1 Android may crash on open sometimes (the
Compose race; fixed in D-191); (2) real Apple/Google sign-in must be tried on his phones once 0.4.0 exists; (3) nothing
syncs until units 4-5. NEXT: unit 2 = removals + guarantees (plan section to write first: ~40 files per shell reference
the relay layer; keep the `ledgerChanged()` hook as a no-op seam for unit 4; invite SCREENS kept for unit 5 to rewire).

## NOW — 2026-09-30 night: D-184..D-190 SHIPPED, 0.3.1 BUMPED; founder is uploading 0.3.1 (READ THIS FIRST)

**Shipped today (all pushed, no CI run):** D-184 `fe60353`; D-185 `38b546c`+`33d1c3d`; D-186 `71e19e5`+`ace44ea`;
**D-187 `2139d1c` + its record** (group picture: photo or emoji; a photo fills the Groups card and the top of the
group page under a gradient, words white at ≥4.759:1 on any photo — the founder's mid-unit ask; photo 3:2 768×512
stepping down to 640/512/384 for busy photos; PICTURE_MAX_BYTES 64,000). Gate 20/20 14m13s; sabotage 15/15 (I4 on
its 2nd run: its pixel test could not tell RGBA from BGRA — fixed, recorded in §3).
**D-188 SHIPPED** `aaeaa91` + record (sign-in page: hero + 3 steps; Google pill art scaled 231x54 pt / 234x52 dp, Apple pill same size; gate 20/20 10m32s; sabotage 6/6).
**D-189 SHIPPED** `484a311` + record (Activity + bell; gate 20/20 10m54s; sabotage 13/13, A2 on its 2nd run).
**Version rule DECIDED (founder 19:50 "do what's best, and record it"):** CLAUDE.md §10.3 — Android versionCode
derived (0.3.1 → 3001), iOS build +1 per upload (0.3.1 → 5). Commit `01055af`.
**D-190 SHIPPED** `b11f638` + record (notifications: opt-in background check, "New activity in X"; gate 20/20 9m31s;
sabotage 7/7). iOS background LAUNCH unproven (residue (a)): to test on a real iPhone via TestFlight.
**ALL TEN ITEMS + 2 MID-TURN DONE (D-184..D-190).** 0.3.1 VERSIONS BUMPED in the tree (iOS 0.3.1 build 5, Android
0.3.1 / 3001; built and read back from both apps). NEXT: the founder builds and uploads; notes
drafted in .drafts/release-0.3.1-notes.md; NO relay deploy; remind Developer Tools exemption OFF before signed builds.
**(old) NEXT: D-190** background checks + LOCAL notifications (plan `.drafts/d189/plan.md`; words per SPEC §3.8 "New
activity in Goa Trip" only; opt-in asks the permission; Android JobScheduler 15 min + POST_NOTIFICATIONS on the Play
manifest allowlist; iOS BGAppRefresh). Then 0.3.1 hand-off (paste-ready notes; no relay deploy).
(old line:) **THEN D-189** notifications (founder: "chose what's best" + a bell): in-app bell list + LOCAL notifications from
background checks (Android JobScheduler ~15 min, iOS BGAppRefresh when iOS allows); no APNs/FCM; Android 13+
POST_NOTIFICATIONS needs the Play manifest allowlist; tell him "every few seconds" only works while the app is open.
Then offer **0.3.1**: iOS build **5**; Android versionCode **3001** (DECIDED 2026-09-30 19:55, founder: "do what's best, and record it" → CLAUDE.md §10.3); paste-ready TestFlight
What to Test + Play notes; NO relay deploy (no server change in D-184..D-187).
**Owed in chat:** TestFlight screenshot feedback checks (said 2026-09-30 evening); Developer Tools exemption still
unconfirmed off. Local state: emulators OFF, relay OFF, iOS 27 sim has --groups-proof data (fine to wipe).

## NOW — 2026-09-30 afternoon: 0.3.0 TESTER FEEDBACK (READ THIS FIRST)

The founder's ten points (+2 mid-turn) from Android friends and his own two-phone test. Status:
**D-184 SHIPPED** (items 1, 2, 3-docs, 8): Android keyboard + edge to edge, split errors in words (live line), the
erase notice (and iOS leaves Settings after an erase). Gate 20/20 in 11m17s with the emulators OFF (D-183's was 29m21s
with one running: item 3 confirmed), sabotage 8/8. **FOUNDER DECISIONS 2026-09-30:** notifications = "chose what's best"
+ the bell (plan: in-app bell list + LOCAL notifications from background checks: Android JobScheduler ~15 min, iOS
BGAppRefresh when iOS allows; no APNs/FCM; Android 13+ POST_NOTIFICATIONS needs the Play manifest allowlist);
remove a person = only if no history (also: never joined, so no key rotation; a hide flag in the projection, not a
delete); add member = name + invite link (+ D-160 in-app), no email lookup; group picture = photo or emoji.
**D-185 SHIPPED** (code 38b546c, gate 20/20 15m22s on it, sabotage 14/14): Your phones, states from shared-with/declined, fresh session, names as an encrypted DeviceName record. **D-186 SHIPPED** (code 71e19e5, gate 20/20 14m42s, sabotage 13/13). **NEXT: D-187** group picture: core draft .drafts/d187/apply_core.py (GroupPicture op: emoji or JPEG hex ≤ 40,000 bytes; projection `picture`), Android draft GroupPictures.kt; iOS twin to write. Then D-188 sign-in page, D-189 notifications. (Old D-186 note: drafts in .drafts/d186/apply_core.py (RemoveMember op, projection `removed`, ffi_member_has_history, tests); shells: rebuildGroups filters removed, LedgerStore.removableMembers/removeMember, People screen X + confirm, group page user-plus glyph (Lucide user-plus read from upstream 2026-09-30: path M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2, circle 9,7 r4 → M9 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8, lines M19 8v6, M22 11h-6; NOTICE six→seven drawings). (old D-185 plan: (apply_core.py, apply_shells.py, DeviceNames.kt/
.swift, DeviceApprovalScreen.kt/.swift, DevicesTest.kt, ios_tests.swift; the old buildDeviceRows tests in
RecordSyncTest.kt / RecordSyncTests.swift must be removed). Then D-186 members (+ glyph, remove), D-187 group
picture, D-188 sign-in page, D-189 notifications.
1. **Android screen jumps while typing — ROOT CAUSE PROVEN, FIX IN TREE (uncommitted, D-184).** The window ran
   `sim={adjust=pan}` (dumpsys): Android slid the whole window to keep the cursor visible while the form's scroll also
   brought the field into view; they re-settled per keystroke (row moved 1043->1092 px on the first key). SECOND
   finding: on Android 15+ (targetSdk 37 => edge-to-edge enforced; Pixel_10_Pro_XL AVD = API 37) the account glyph sat
   on the battery icon and the tab bar on the gesture handle. Fix: manifest `windowSoftInputMode="adjustResize"`,
   `enableEdgeToEdge` keyed on SKIPTI's own dark mode, root Box padded by `WindowInsets.safeDrawing` (AppRoot.kt
   MainActivity). Looked: API 34 window now `adjust=resize`, rows still across 6 keys; API 37 bars clear
   (.drafts/fb/p34-typing.png, p37-after.png). Test + sabotage + dark-mode look still owed.
2. **"expected=3000000, got=2500000"** — the maths was right (500+4500+10000+10000 = 25,000 of 30,000); both shells
   show core's raw uniffi message in minor units (Android `AddExpenseUi.humanError`, iOS `"\(e)"` in the save
   catch). Fix owed: plain words with core-formatted amounts ("The amounts add up to ₹25,000.00 of ₹30,000.00;
   ₹5,000.00 is left") for every split CoreError, twins, + ideally a live "left to assign" line.
3. **Slow gates** — D-182/D-183 gates ran EVERY leg ~3x slower than D-17x (release sweep 771 s vs 200 s, ios-app 205 vs
   52). Cause: my headless emulator used `-gpu swiftshader_indirect` (software rendering, ~80% CPU per the founder)
   and stayed up. With `-gpu host` the idle emulator is ~9% CPU. Rule owed in CLAUDE.md §7.2: boot with `-gpu host`,
   and `adb emu kill` after every visual check, before any gate. (Claude Code now runs from 2.1.284's folder: check
   the exemption still reaches it on the next gate.)
4. **Devices screen** — two root causes, both shells: (a) "Approved" = device id in `listOwnEnvelopes`, but the relay
   filters envelopes PER RECIPIENT (lib.rs envelope_list), so another device can never read as approved, and this
   device never either (it received, it did not send); approve says "Approved" while the row stays "Not approved".
   (b) the screen uses `restoreSession()` (no `freshSession`), ACCESS_TTL is 15 min, a 401 returns `emptyList()`
   silently => rows vanish ("this morning it's not showing the android"). Also the screen predates D-178 automatic
   sharing. Fix owed: fresh session, errors shown, approved = authority==account for self and the local
   shared-with/known maps for others, copy for automatic sharing.
6. Device make on Devices — design: an ENCRYPTED personal record (device id -> "iPhone 17"), never plaintext on the relay.
8. Erase — the confirm dialog DOES appear (Android emulator, read on screen); likely means no confirmation AFTER the
   erase. Add a "This phone's ledger was erased" notice on the setup screen.
11. (mid-turn) Remove a person: the New group form HAS an X per row on both shells; an EXISTING group has no removal at
    all (core has no RemoveMember op; SPEC §3 says removal rotates the group key). Fork to the founder.
12. (mid-turn) TestFlight screenshot feedback not reaching App Store Connect: nothing in the app touches it (ScreenPrivacy
    only covers screen RECORDING, default off). Console-side; checks given in chat.
5, 7, 9, 10 + 11: features — forks put to the founder 2026-09-30.
Emulators: skipti-shots (5554) and Pixel_10_Pro_XL (5556) are RUNNING headless with -gpu host; kill before any gate.

## NOW — 2026-09-30 (end of session; READ THIS FIRST tomorrow)

**0.3.0 IS OUT to internal testing** (the founder, 2026-09-30: "Deployed and sent for internal testing"). Relay live at
`2160d90` (verified: `curl https://skipti.vardrlabs.com/version` → commit 2160d909…). Tree clean at `2160d90` plus this
WORKLOG commit.

**FOUNDER RULE FOR THE NEXT VERSION NUMBERS (2026-09-30, his words):** "next time you write version number, make it 5
for iOS because I pressed enter twice and iOS built the core twice so now it read version 3 build 4, so having version
5 for it will sync it, you can do the same in android for evenness." So the NEXT release: **iOS build
(CURRENT_PROJECT_VERSION) = 5** in both XC0011 and XC0012 (0.3.0 used builds 3 and 4). **Android: to confirm with him
before bumping** what "the same for evenness" means: Play refuses a versionCode lower than 3000 (so a literal 5 is
impossible); the D-116 scheme gives 0.3.1 → 3001; the likely reading is "bump both together in the same release".
Ask in one line when the next release comes, recommending 3001 (or 3005 if he wants the last digit to match iOS).

**FOUNDER RULE FOR EVERY RELEASE (2026-09-30):** "give me a list of improvements to write before I upload in
testflight because it keeps removing the defaults". Every release hand-off includes, in the chat, a paste-ready
"What to Test" list (iOS) and release notes (Android, under Play's 500 characters) built from the changelog entries
since the last release.

**Xcode's archive rewrote two files after the D-183 commit** (the D-177 pattern): pbxproj entries re-sorted and
`CURRENT_PROJECT_VERSION` back to 3; Info.plist comments dropped (values identical by `plutil`). Saved to
`.drafts/release-0.3.0/` and restored to the committed files. Nothing else changed.

**OPEN (the founder's):** the **Developer Tools exemption** (CLAUDE.md §13): he has not said it is off. If he signed
0.3.0 with it on, it should still go off before any external beta or store build; ask once tomorrow.
**Later (his list):** assetlinks.json with the upload key; Recovery Kit restore unit before public release; themes
(Vardr Labs, Norse); tutorial when he says; email sender for the relay. Tester feedback on 0.3.0 comes first.

## (older) NOW — 2026-09-30 ~03:35 EDT — REDESIGN DONE; 0.3.0 READY FOR THE FOUNDER

**Shipped this session:** D-182 (`911209e`, Settle up across every group) and D-183 + 0.3.0 (this commit: the Add sheet
in the canvas's look; iOS 0.3.0 build 4, Android 0.3.0 / 3000; gate 20/20 in 29m21s, sabotage 4/4). The founder's
redesign from the canvas is complete: D-179 bar + Groups, D-180 Balance across groups + group page, D-181 Expenses by
group + two-step category, D-182 Settle up, D-183 Add.
**WAITING ON THE FOUNDER (his actions, never Claude's):** (1) deploy the relay from this commit (`deploy/ship-relay.sh
ubuntu@<IP>`; D-178's PUT /v1/session/device), then check /version; (2) **Developer Tools exemption OFF** before the
signed builds (CLAUDE.md §13; open until he confirms); (3) build + upload per RELEASE-INTERNAL-TESTING §5.
**Later (founder's list):** assetlinks upload key; Recovery Kit restore unit before public release; themes; tutorial;
relay email sender.

## (older) NOW — 2026-09-30 ~03:00 EDT — D-183 + 0.3.0 APPLIED, LOOKED; SABOTAGE NEXT

D-182 shipped `911209e`. D-183 applied (both scripts) + two fixes found by looking: Group moved up beside Category
(canvas order, both shells); iOS `.scrollDismissesKeyboard(.interactively)`; Android's "0" hint lightened. Tests green
(AddSheetTest 2/2, iOS testTheAddSheetSummaryIsCoresEffectInWords). LOOKED: iOS + Android, Paris -> Goa trip switches
people and currency (EUR -> INR); 2400 -> "You’ll get back ₹1,800.00" (the canvas's number). Screens:
ios/artifacts/d183-*, android/artifacts/d183-*. SABOTAGE 4/4 (.drafts/d183/results_d183.json). Templates ready
(.drafts/d183/final). NEXT: gate (.drafts/d183/gate.log), record_unit.py --unit D-183 --prev D-182, commit, push, then
the founder's deploy hand-off.

## (older) NOW — 2026-09-30 ~02:50 EDT — D-182 SHIPPED; D-183 + 0.3.0 NEXT

**D-182 SHIPPED:** gate 20/20 (25m05s), sabotage 7/7, recorded (this commit). **NEXT: D-183 + 0.3.0 in one unit:**
`python3 .drafts/d183/apply.py` (Add sheet, both shells), `python3 .drafts/d183/apply_release.py` (versions 0.3.0 /
build 4 / 3000 + RELEASE-INTERNAL-TESTING §5: relay deploy step, what to test), copy `.drafts/d183/AddSheetTest.kt` to
android/app/src/test/.../ and append `.drafts/d183/ios_test.swift` into BalanceLineTests before its last `}`; build,
look (--groups-proof: Add sheet, switch group, summary line), sabotage, gate, record, commit, push; then hand the
founder the relay deploy commands and the Developer Tools reminder.

## (older) NOW — 2026-09-30 (after compaction) — D-182 APPLIED, BUILDING

**D-182 applied (uncommitted):** `ios/Skipti/App/SettleAll.swift` (SettleAllCopy, SettleSection, SettleAll.sections /
.settlement, SettleAllScreen; pbxproj A10135/B10150, `<group>` like OverviewScreen), `android/.../SettleAll.kt`
(SettleAllCopy, SettleSection, SettleAll.sections, SettleAllScreen), HomeShell + AppRoot settle tab -> SettleAllScreen
(SettleScreen kept for --settle). Tests: `SettleAllTest.kt` (2) + iOS `testSettleUpCoversEveryGroup` in
BalanceLineTests; goldens `.drafts/d182/golden.py` (Goa Rahul 180000 / Meera 260000 / Sam 300000; Flat Meera 45000 /
Kiran 90000; Paris Ana -3200 EUR). Tests green (JVM 2/2, iOS BalanceLineTests 23/23). LOOKED on both phones, light +
dark: amounts match the goldens; "I paid Ana" -> "Paris is all settled". SABOTAGE 7/7 (.drafts/d182/results_d182.json).
Record templates ready: .drafts/d182/final. NEXT: full gate (.drafts/d182/gate.log, gate.start), record_unit.py
--unit D-182 --prev D-181, commit, push. No core change (no bindings).
**D-183 READY:** `.drafts/d183/apply.py` (dry run clean, 16 anchors: AddSheetCopy both shells, big centred amount +
currency pill, "What was it for?", Group picker in the sheet, live summary via ffiYourEffect), tests
`.drafts/d183/AddSheetTest.kt` + `.drafts/d183/ios_test.swift` (append to BalanceLineTests). Apply AFTER the D-182
commit.

## (older) NOW — 2026-09-30 ~01:45 EDT (context compaction next; READ THIS FIRST)

**Shipped this session (all pushed, [skip ci], no Actions runs):** D-178 (`1e21b9e`, RELAY CHANGED), D-179 (`84462aa`),
D-180 (`b63fb9c`), D-181 (`e798bf3`, gate 20/20 in 33m20s, sabotage 8/8, recorded in this commit). The founder
(2026-09-30): "we can do the remaining then to completion". **Full resume notes: `.drafts/RESUME-2026-09-30.md`.**

**REMAINING, in order:**
1. **D-182 Settle up across groups.** Drafts READY: `.drafts/d182/SettleAll.swift`, `.drafts/d182/SettleAll.kt`
   (SettleAllCopy + SettleAllScreen: a section per group, "Rahul pays you" / "You pay Ana", "Mark as paid" / "I paid
   Ana", "X is all settled", fastest plan; records via the existing settlement path). Apply: Swift -> ios/Skipti/App
   (pbxproj 4 insertions, IDs after A10134/B10149, assert unused), Kotlin -> android/.../SettleAll.kt; HomeShell
   settle tab -> `SettleAllScreen().shellRoot(.settle, chrome)`; AppRoot HomeTab.SETTLE -> `SettleAllScreen(vaultPath,
   dark)`; keep SettleScreen for --settle proofs. Twin tests for the copy/verbs; look with --groups-proof; sabotage;
   gate; record; commit.
2. **D-183 Add sheet, canvas look:** big centred amount ("How much?") + currency pill, "What was it for?", a Group
   picker in the sheet, a live summary from core ffi_your_effect ("You'll get back ₹1,800.00"); keep split kinds,
   payer, people, two-step category, edit mode. Both shells.
3. **RELEASE 0.3.0:** iOS MARKETING_VERSION 0.3.0 build 4 (XC0011 + XC0012), Android versionName 0.3.0 / versionCode
   3000; RELEASE-INTERNAL-TESTING §5; relay deploy commands for the founder (deploy/ship-relay.sh; D-178's PUT
   /v1/session/device); remind Developer Tools OFF before signing.
4. Later: assetlinks upload key; Recovery Kit restore unit; themes; tutorial; relay email sender.

## (older) NOW — 2026-09-30 ~00:40 EDT

**D-181 BUILT + LOOKED, NOT GATED:** both apply scripts applied (+ fixes: a brace the script dropped, the iOS row
style, one-line detail text, Android imports); bindings regenerated; `make ios-core`/`android-core` (the emulator
first ran an APK with the OLD .so: CLAUDE.md §7's trap, "undefined symbol ffi_your_effect"). Looked: iOS + Android
Expenses by group with chips, "you owe / you lent"; Category -> Sub-category (Travel -> Flights, Trains, Buses...) on
both. JVM 278x2; iOS BalanceLineTests/CategoryWiringTests/AddExpenseTests green. NEXT: sabotage
.drafts/d181/sabotage_d181.py (8 rows), commit code, gate on HEAD (bindings changed), record, then D-182 (Settle up
across groups + the Add sheet's canvas look), then 0.3.0.

## (older) NOW — 2026-09-30 ~00:15 EDT

**D-180 SHIPPED:** code `b63fb9c`, gate 20/20 on it (33m46s), sabotage 13/13, recorded (this commit). **NEXT: D-181**
= the founder's two asks: (a) two-step category picker (`python3 .drafts/d181/apply_categories.py`, dry run clean, 7
files) and (b) Expenses grouped by group + core `ffi_your_effect` for "you lent / you owe"
(`python3 .drafts/d181/apply_expenses.py`, dry run clean, 5 files; then `make bindings`, `make ios-core`,
`make android-test`). Then D-182: Settle up across groups + the Add sheet's canvas look. Then 0.3.0.

## (older) NOW — 2026-09-29 ~23:25 EDT

**D-180 BUILT + LOOKED, NOT GATED:** core `core/src/overview.rs` (ffi_overview: per-currency totals never converted,
per-group nets, people merged by case-folded name within a currency; ffi_group_stats: spent / your share per currency,
categories by PARENT with largest-remainder basis points; 5 tests, goldens .drafts/d180/golden.py); bindings regenerated;
iOS OverviewScreen.swift (Balance hero/People/By group; GroupPageScreen) + Android OverviewTab.kt (OverviewScreen,
GroupPage inside the tabs); `--groups-proof` now seeds 6 expenses. FOUND BY LOOKING: (1) the custom bar's inset never
reached a tab's scroll views (TabView and a ZStack both; padding the scroll view itself worked) -> iOS HomeShell is a
ZStack of 4 stacks, tab ROOTS reserve room (shellRoot), pushed pages hide the bar except the group page (ShellChrome,
keepsShellBar); (2) Android stat boxes clipped; (3) hero chip film 18% = 4.35:1 over the rose stop -> 12% (4.9:1,
tested). **FOUNDER (2026-09-29 ~23:15), mid-D-180:** (1) bar order Balance · Expenses · + · Groups · Settle up (DONE in
D-180, tests updated); (2) Expenses tab GROUPED BY GROUP, aesthetic (-> D-181); (3) edit expenses from the group page
(DONE: Recent rows = core projection, open the expense detail with Edit/Delete, both shells). Also pending for D-181:
the TWO-DROPDOWN category picker (founder, earlier). JVM 277x2 green; iOS BalanceLineTests+AppearanceTests green.
NEXT: sabotage .drafts/d180/sabotage_d180.py (13 rows), gate (bindings leg will fail pre-commit: commit then gate on
HEAD as in D-178), record (.drafts/d180/final to write), commit; then D-181; then 0.3.0.

## (older) NOW — 2026-09-29 ~22:00 EDT

**D-178 SHIPPED:** code `1e21b9e` (pre-commit full gate 19/20, bindings leg only, as expected; hook fast gate 10/10),
full gate on the committed tree 20/20 in 10m27s, recorded in BUILD-LOG (this commit). Sabotage 23/23 (run 2 17/23 +
6 stale predictions corrected and re-run 6/6). Relay changed: DEPLOY WITH THE 0.3.0 RELEASE (not before; founder).
**D-179 BUILT + LOOKED (2026-09-29 ~22:40):** applied (.drafts/d179/apply.py), JVM 273x2 green, iOS BalanceLineTests +
AppearanceTests green. `--groups-proof` added on both shells (seeds Goa trip/Flat 4B/Paris on an EMPTY ledger, opens
Groups). Looked: iOS 27 sim dark+light (Groups, Balance after picking Goa, Add sheet with Cancel), emulator dark+light
(Groups, Balance, Add page with Cancel); artifacts ios/artifacts/d179-*.png (4), android/artifacts/d179-*.png (3).
**FOUND BY LOOKING:** the Groups tab's Aurora glow grew from nothing and swung forever: D-177's `.animation(_:value:)`
on the backdrop ZStack still animated the blooms' SIZE/PLACE when a tab laid out in the same update that started the
drift. Fixed: frame + base offset laid out unanimated, only the drift offset inside iOS 17 `animation(_:body:)`. The
founder's kit-step swing (D-177) may have had this same path: record as a correction of D-177. Also seen (pre-existing,
for D-180): Android Balance paints its own glow over the shell's (a seam under the account bar); Android Balance has no
title. SHIPPED (this commit): sabotage 9/9 first run, gate 20/20 in 25m59s (release sweeps rebuilt core after sabotage
restores: 741 s), recorded; D-177's §3 carries a (Corrected D-179) note. **FOUNDER (2026-09-29, during D-180): category picker becomes TWO dropdowns (Category -> its sub-categories), in
THIS build (0.3.0); planned into D-181's Add sheet (.drafts/d179/PLAN.md, end).** **NEXT: D-180** (plan .drafts/d180/PLAN.md:
core ffi_overview + ffi_group_stats, Balance across every group with the hero, People, By group; the group page).

## (older) NOW — 2026-09-29 ~22:30 (after compaction)

**D-178 finishing:** refusal + personal-scope assertions for the Security record added (both shells; GroupSyncTest
a_groups_log_brings_in_only_that_groups_records + every_group_gets_one_key_once_and_it_is_personal, iOS twins).
Sabotage run 2 (23 rows, relay stopped): 17/23 first pass; the 6 mismatches (I4-I6, A4-A6) were STALE PREDICTIONS
written before the two account-wide tests existed (each red set = the corrected one); predictions corrected, re-run
6/6; merged .drafts/d178/results_d178_final.json 23/23; tree identical. Docs APPLIED (ADR-0039, GUARANTEES #7,
THREAT_MODEL, SPEC §3.5, CLAUDE.md). NEXT: full gate (bindings leg compares HEAD -> expect only that leg red before
the commit), commit code, full gate on HEAD, record (templates .drafts/d178/final), commit record, push.
**THEN D-179** (drafts ready: .drafts/d179/{PLAN.md,HomeShell.swift,TabBar.swift,GroupsScreen.swift,GroupsTab.kt,
TabBar.kt,tones.py}; Lucide wallet/users/circle-check verified against upstream 2026-09-29). The founder (2026-09-29,
after compaction): the next ship carries the new UI he validated on the canvas.

## RESUME HERE — 2026-09-29 ~19:30 (context compaction; superseded by the NOW block above)

**Founder (2026-09-29):** finish D-178 after compaction; THEN ship the next version (relay deploy + Android + iOS) TOGETHER
WITH the new UI (the five-tab redesign, D-179+) in one release. Do not ship 0.2.1 separately.

**D-178 state: BUILT, NOT GATED, NOT COMMITTED** (HEAD is still 717480c = D-177). Uncommitted tree (all D-178):
- relay: server/src/{lib.rs (session_device_put + route PUT /v1/session/device), auth.rs (attach_sync_device_once +
  unit test a_session_keeps_the_first_device_it_declares), api_types.rs (SessionDevicePut)}; server/tests/
  session_device_http.rs (5 tests). NEEDS FOUNDER DEPLOY (deploy/ship-relay.sh) with the release.
- core: GroupOp::Security{maximum} (groups.rs: validate, projection maximum_security = latest write, 2 tests), ffi.rs
  (GroupOpIn::Security, GroupsProjectionOut.maximum_security); bindings REGENERATED (skipti_core.kt, skipti_core.swift).
- Android: RelayTransport.kt (PUT carries JSON body — THE 415 BUG), DeviceKeyService.kt (attach, holdsAccountKey,
  shareWithNewPhones, NewPhone, ShareNotice, decline, maximumSecurity via projection + record, KeyPayload.known,
  push-cursor reset, remember giver), LedgerSync.kt (SyncReport refresh(), tolerant pull + lastUnreadable,
  lastGroupApplied; refreshInBackground removed), SyncStatus.kt (NEW: revision/holdsKey/newPhones/notices/syncing,
  10 s poller), SyncPrompts.kt (NEW: DeviceShareCopy, SyncBanners, NewPhoneDialog, MaximumSecuritySetting),
  AppRoot.kt (poller on resume/pause, SyncBanners, PullToRefreshBox, join-account-proof), AccountHubScreen.kt (switch),
  Balance/ExpenseList/Settle screens (LaunchedEffect keyed on SyncStatus.revision), GroupSync.kt + LedgerStore.kt
  (Security is PERSONAL), InviteJoin.kt (ProofSession.mint identifier; JoinAccountProof).
  Tests: RecordSyncTest +5 (phones_signed_in_with_google..., maximum_security_asks_first..., a_phone_that_synced_under
  _its_own_key..., maximum_security_is_the_accounts_choice..., a_third_phone_does_not_announce...), GroupSyncTest +1 live
  (a_second_phone_on_the_same_account_gets_its_groups_and_expenses), RelayTransportTest (NEW). JVM 269 x 2 GREEN.
- iOS: IdentityService.swift (transport PUT content-type), DeviceKeyService.swift (twin), LedgerSync.swift (SyncReport,
  tolerant pull, SyncStatus @Observable + poller skipped under XCTest, DeviceShareCopy), HomeShell.swift (.refreshable x3,
  approval .alert, SyncBanners view), AccountHubScreen.swift (MaximumSecuritySetting), SkiptiApp.swift (poll on
  scenePhase, join-account-proof), InviteJoin.swift (JoinAccountProofScreen, mint identifier), Balance/ExpenseList/
  Settle (.task(id: revision)), GroupSync.swift (.security personal). Tests: RecordSyncTests +5 twins, GroupSyncTests
  live twin + maximumSecurity field, RelayClientTests.swift (RelayTransportTests class + CapturingProtocol).
  iOS: after the Security/known change and `make ios-core`, RecordSyncTests + GroupSyncTests + RelayTransportTests:
  TEST SUCCEEDED, 30 passed, 0 failed, 0 skipped (.drafts/d178-ios-4.log, relay running).
- docs drafts (NOT applied): .drafts/d178/docs/0039-new-phones-join-automatically.md (updated: account-wide) and
  apply_docs.py (GUARANTEES #7 + Amendments, THREAT_MODEL section, SPEC §3.5 note, CLAUDE.md ADR count/#7/PUT rule)
  — apply_docs.py's GUARANTEES text still says "Turn on Maximum security (Settings → Privacy & security)": fine; add
  "for every phone on your account" if desired.
- artifacts (untracked, keep): ios/artifacts/d178-{shared-notice,group-from-android,maximum-security}.png,
  android/artifacts/d178-{waiting,joined-group-arrived}.png; also want the popup shot (.drafts scratch i-ask.png is in
  /private/tmp scratchpad — may be lost; retake with 3 phones).
- sabotage: .drafts/d178/sabotage_d178.py (16 rows) run1 14/16 + rerun R1/R2/I3 OK. MUST RE-RUN ALL after the
  Security/known change (A4/I4 anchor `if (maximumSecurity(store))` still valid) and ADD rows: core Security latest-write
  (sabotage write() order) -> the 2 core tests; account choice not refused from group logs (GroupSync PERSONAL) -> need
  a test first (none yet!); KeyPayload.known ignored -> a_third_phone... test. Stop the local relay before sabotage.

**NEXT STEPS (in order):** 1) (iOS sync classes: DONE, 30/30); 2) add a refusal test for Security from a group log (both shells)
3) sabotage full re-run + new rows; 4) apply docs; 5) ask founder to confirm Developer Tools ON, full gate; 6) record
D-178 (.drafts/d178/final templates to write; record_unit.py), commit, push; 7) then the redesign D-179 (five tabs etc.,
plan below), then ONE release: bump to 0.3.0 (iOS build 4, Android 3000), relay deploy commands, runbook §5.
Running at compaction: local relay (target/debug/skipti-server, dev mint), emulator skipti-shots, sims AFAF + 0EAE
booted; the LG is plugged in (use ANDROID_SERIAL=emulator-5554).

## NOW — 2026-09-29 evening (READ THIS FIRST)

**Google on Android WORKS** (founder: his Android client had package `com.vardrlabs.com`; fixed in the console).

**NEW BUG (founder, 2026-09-29):** iPhone signed in with Google (the founder's Google account), made a group + $100 expense; Android signed
in with the same Google account and saw NOTHING. **ROOT CAUSE (code, both shells):** Apple and Google sign-ins send
`sync_device_id: null` (GoogleSignIn.kt:52, GoogleSignIn.swift:151, AppleSignIn.swift:45; deliberate so D-168's
other-account notice works), so the relay session has no device; `PUT v1/devices/agreement-key` needs one (lib.rs
agreement_key_put -> 401); `DeviceKeyService.bootstrap` swallows it; the authority marker stays ABSENT, so each phone
pushes with its OWN random `sync/secret` (ensureSecret at sign-in) and no device can ever be approved (D-123/D-124's
key sharing is unreachable for provider sign-ins). Also: pullAndMerge THROWS on the first entry it cannot open, so
one stray-key entry blocks the whole pull (refresh() swallows it: nothing applied). Link invites to friends do not
depend on the session device (admit/envelope bodies claim devices); in-app invites (D-160) need agreement keys: broken
the same way. restoreFromRelay is proof-only; production pulls via refresh() on foreground (both shells).

**FOUNDER DECISIONS (2026-09-29 evening, AskUserQuestion):** new phones on the same account get the data
**AUTOMATICALLY by default** (the old phone wraps the account key to any new device's published key on its next sync,
no tap); a Settings switch **"Maximum security"** (default OFF) turns on popup approval with six words on both phones;
with it off the old phone still shows a **notice** when it shares ("Your account's data was shared with a new phone
... Not you? Turn on Maximum security and sign out that phone."). He accepts the policy change: **GUARANTEES #7 is
amended** (the relay operator could read synced data unless Maximum security is on) -> ADR-0039 + THREAT_MODEL +
SPEC §12 note. Also: auto-sync every few seconds in the foreground (chosen: 10 s) + pull to sync.

**D-178 PROGRESS (2026-09-29 night):** relay `PUT /v1/session/device` built + 5 tests (server/tests/session_device_http.rs);
both shells: attach in bootstrap, refresh() heals/shares/reports (SyncReport), tolerant pull, push-cursor reset on a new
key, auto-share + notices + Maximum security (kv sync/maximum-security, sync/shared-with, sync/declined,
sync/share-notices), SyncStatus (10 s poll, revision -> screens reload), banners + approval popup, pull to sync,
Settings switch. JVM RecordSyncTest +3 (18/18), iOS RecordSyncTests +3 (16/16). **SECOND ROOT CAUSE, FOUND BY THE
LIVE TEST** (GroupSyncTest.a_second_phone_on_the_same_account_gets_its_groups_and_expenses against a local relay):
BOTH transports sent a JSON body/content-type only for POST, so every PUT (agreement-key publish since D-123, and the
new attach) got **415 from the real relay**: no phone ever published a key in production; the fake relays see no
headers. Fixed in HttpUrlConnectionTransport + URLSessionRelayTransport; wire tests RelayTransportTest (JDK
HttpServer) and RelayClientTests.testEveryMethodButGetSendsItsBodyAsJson (URLProtocol). Live test now PASSES on
Android. Next: iOS live twin, sabotage, look (sim + emulator against local relay), docs (ADR-0039, GUARANTEES #7,
THREAT_MODEL, SPEC §12), gate, record, deploy hand-off (relay change!).

**D-178 PROGRESS 2 (2026-09-29, ~19:00):** sabotage run 1: 14/16 (R1 wrong prediction: handler checks first; unit test
auth::tests::a_session_keeps_the_first_device_it_declares added; I3's extra red = AccountOwnerTests erase test hit "disk
I/O error" because the NEW 10 s poller ran inside the XCTest host -> startPolling() now skips when
XCTestConfigurationFilePath is set); rerun R1 R2 I3 3/3. LOOKED (local relay, --join-account-proof family1 on iOS 27 sim
+ skipti-shots + iOS 26.5 sim): Android waiting banner with six words; iPhone auto-shared + notice with the SAME words;
Android group "Weekend" appeared on the iPhone by itself; Settings Maximum security switch; approval popup with the
third phone's words. FOUND BY LOOKING: (1) Maximum security was PER PHONE, so the Android (off) shared with the third
phone while the iPhone (on) asked -> now an ACCOUNT record: core GroupOp::Security{maximum} (personal scope, latest
write wins, GroupsProjectionOut.maximum_security), bindings regenerated, shells read the projection, write a record +
push, refuse it from group logs (PERSONAL); (2) the third phone announced the iPhone as "new" -> KeyPayload.known (the
giver's shared-with map, unsealed, older builds ignore it). New tests: core 2, JVM +2 (maximum_security_is_the_accounts
_choice_on_every_phone, a_third_phone_does_not_announce_the_phones_already_there), iOS twins. JVM full suite 269 x 2
green with the relay running. Sabotage must be RE-RUN (A4/I4 anchors changed) + new rows for the account choice.
Proof arg `--join-account-proof <name>` both shells (note: adb shell needs the args quoted for the DEVICE shell:
`adb shell "am start ... --es args '--join-account-proof family1 --relay-local'"`; LG plugged in -> ANDROID_SERIAL).
Docs drafted in .drafts/d178/docs/ (ADR-0039, apply_docs.py) — update for the account-wide switch before applying.

**D-178 PLAN ("Your phones share one account"):** relay `PUT /v1/session/device` (attach the phone's device id to a
device-less session once, after the app's own D-168 check; claim_device rules; idempotent; different device -> 401;
tests; FOUNDER DEPLOYS). Shells (twins): bootstrap attaches first; refresh() runs bootstrap when authority != account
(heals existing sessions); receiving a key that differs from the local secret resets this device's push cursors so its
own records re-push under the account key; pull skips entries it cannot open once authority == account (count logged),
still throws when never authorised (D-123 test); auto-approve pending devices (keys without an envelope) when Maximum
security is off + notice; popups when on; waiting-phone banner with six words; Settings switch; 10 s foreground poll;
pull-to-refresh on Balance/Expenses; screens reload after a pull. Then the redesign (D-179+).

## NOW — 2026-09-29 (READ THIS FIRST)

**Founder's device report on 0.2.0 (2026-09-29):** (1) iOS: the Recovery Kit step inside sign-in "moves up and down
automatically"; asked, the WORDS AND BUTTONS move (not only the glow). (2) Google sign-in WORKS on iOS. (3) Android
(LG, Play 0.2.0) still "Google closed without signing you in." The LG's install cert SHA-1 50:10:CF:… (SHA-256
57:F3:A1:…) IS registered as an Android OAuth client (founder), so this is not the missing-SHA-1 case any more.
His logcat came from an EMULATOR (EGL_emulation lines, pid 5825), not the LG, and "16:" matched every timestamp: the
runbook's command was wrong (fixed: `adb -d logcat -d -s GoogleCredential`). His assetlinks.json used 57:F3 as BOTH
UPLOAD_FP and OLD_FP; the upload key is 41:84:18:7C:… (keytool on the AAB), so assetlinks lacks the upload key and the
direct entry carries the wrong key (corrected command given in chat; fingerprints stay out of the repo).
**Developer Tools exemption: the founder turned it OFF for signing** and asked to be told BEFORE any gate so he can turn
it back on for Claude. Do not start a gate without asking.

**GOOGLE ON ANDROID, ROOT CAUSE PROVEN (2026-09-29, over USB on the LG, with the founder's permission):** LG = Android 12
(API 31), SKIPTI 0.2.0 (2000) from Play, ONE signer, no rotation lineage: apksigner on the pulled base.apk gives SHA-1
50:10:CF:8B:55:52:47:35:D8:2B:6E:1F:01:F2:F9:79:03:12:6B:E0 (matches About). The app asks for the Web client
403030719865-7bic… with package com.vardrlabs.skipti (checked in the tree). Play services' own log for all three
attempts: `[GetTokenResponseHandler] Server returned error: This android application is not registered to use OAuth2.0,
please confirm the package name and SHA-1 certificate fingerprint match what you registered in Google Developer Console`
-> `status=UNREGISTERED_ON_API_CONSOLE` -> surfaced to the app as `[16] Account reauth failed` (TYPE_USER_CANCELED).
So the founder's console does NOT have an Android client with (com.vardrlabs.skipti, that SHA-1) IN PROJECT
403030719865 (the Web client's project). Likely: the client is in another project, or its SHA-1/package differs by a
character, or it is a different key's SHA-1. Founder action, not code. Emulator Play-services logs are useless here.
Developer Tools back ON for Claude (founder, 2026-09-29, after restarting Claude): gates allowed.

**D-177 SHIPPED (0.2.1; gate 20/20 on run 3 in 7m42s; sabotage 3/3; recorded in BUILD-LOG):**
- iOS kit motion. Root cause: `AuroraBackdrop` started its drift with `withAnimation(.repeatForever) { drift = true }` in
  onAppear, the ONLY perpetual animation in the app; that transaction also carries layout changing in the same update
  (the kit step replaces the sign-in form as the nav bar drops its title after Google's sheet), so the page swung
  forever. His screenshot shows the text ~35 pt lower than the simulator's layout. NOT reproduced on the simulator
  (iOS 27 and 26.5, `--onboarding`, `--signed-in-proof google` on a local relay, and a temporary hook on the real
  Settings -> Sign in path with and without a closing sheet: content still in every frame), so the fix is proven by
  mechanism + the founder's device, not by a sim repro. Fix: `.animation(AuroraBackdrop.drift(reduceMotion), value:
  drift)` scoped to the blooms, drift set with no transaction. Test `testTheGlowStaysStillUnderReduceMotion`.
  Looked: glow still drifts, button rows fixed across 6 frames (ios/artifacts/d177-kit-still.png).
- Android: `CeremonyUnavailable.detail`; `GoogleCredential.reasonFor` puts "Google's reason: <Play services' message>"
  under the sentence, so the LG shows it with no USB. Test `googles_reason_is_shown_under_the_failure` (play).
  Looked on skipti-shots: "No Google account is available on this device. / Google's reason: No credentials available
  on this device." (android/artifacts/d177-google-reason.png).
- Sabotage 3/3: Android play reason dropped -> exactly the new test; Android direct twin `a_refusal_here_quotes_no_provider`
  (added so both flavours run the same count; `GoogleCredential.unavailable()`) given a reason -> exactly it; iOS Reduce
  Motion ignored -> exactly the new test. First full gate stopped at cargo test to add the direct twin; restarted.
  Record drafts: .drafts/d177/final/, results .drafts/d177/results_d177.json.
- 0.2.1: iOS MARKETING_VERSION 0.2.1 build 3; Android 0.2.1 / 2001. Runbook §5 numbers + §5.3 logcat/assetlinks fixed.
- Info.plist: Xcode rewrote it during the founder's archive (comments dropped, keys reordered; plutil JSON identical);
  restored to HEAD.
- Emulator note: skipti-shots wedged after a warm boot (repeated ANR dialogs naming a dead process's window); a cold
  boot (-no-snapshot-load) cleared it. Not an app bug.
**FOUNDER NEXT:** (1) Google Cloud console, project 403030719865 (the one holding the Web client
403030719865-7bic…): Credentials -> an Android OAuth client with package com.vardrlabs.skipti and SHA-1
50:10:CF:8B:55:52:47:35:D8:2B:6E:1F:01:F2:F9:79:03:12:6B:E0 must exist IN THAT PROJECT (if it exists elsewhere, delete
it there first: Google refuses a duplicate package+SHA-1); then retry on the LG after a few minutes. (2) assetlinks.json
with the upload key 41:84:18:… (command in chat 2026-09-29). (3) ship 0.2.1 (runbook §5), iPhone checks the kit step.
(4) Developer Tools OFF before signing, back on after. **CLAUDE NEXT: D-178.** The redesign units renumber: **D-178** five-tab shell + Groups tab
(the plan below that says "D-177"), D-179 all-groups Balance, D-180 Add sheet + Expenses + Settle up.

## NOW — 2026-09-26 evening (READ THIS FIRST)

**Shipped today:** D-172 (device-test fixes, `bc44d84`), D-173 (email code hidden, `41172de`), D-174 (ADR-0038 look,
this commit; found iOS never drew a Space Grotesk weight). Developer Tools now covers Claude Code 2.1.281 (founder,
2026-09-26 evening; syspolicyd idle).

**GOOGLE SIGN-IN ON ANDROID, DIAGNOSED (2026-09-26 evening, founder's Play screenshot):** Play's App signing page shows
the current key "Quantum-ready (beta)" (a Classical key and a Post-quantum key, install base 0.0%) and under "Previous
app signing keys" one key "First used 26 Sep 2026, 01:09". Play moved the app to the quantum-ready key ON ITS OWN (the
founder did not rotate anything; "beta") after the first internal release, so the build testers have is signed with the PREVIOUS key, whose SHA-1 is NOT in any OAuth client
(he registered the two new ones). Fix (founder, console): download the previous key's certificate (download icon on
that row), take its SHA-1, add an Android OAuth client for com.vardrlabs.skipti with it; keep the two new ones (the
next release is signed with the new keys; Android 12 and older keep the old one via rotation). Also assetlinks.json
must carry the NEW classical (and PQC) SHA-256 alongside the old one for App Links (relay deploy, founder). Audience:
External, Testing, test users listed (founder). 0.2.0 adds an About line showing the SHA-1 the installed app carries,
to confirm.

**FOUNDER DECISIONS (2026-09-26 evening):** next build NOW as **0.2.0** (fixes + look + the SHA-1 line), and again after
the redesign. Palette **Aurora**: primary #6246EA violet-indigo, glow #2DD4BF -> #8B5CF6 -> #F472B6, light bg #F5F4FB,
dark bg #0C0B16, money unchanged (#0A7C74 / #C2410C), frosted glass cards, 24pt corners. Glass "everywhere that seems
aesthetic" (battery is not a concern), figures kept legible. Lively motion: tabs pop when selected, springy raised +,
fun touches (respect Reduce Motion). Settings order: You -> Account (sign in / signed in as, sync, devices) -> Money ->
Look -> Privacy & security (app lock, hide in recording, Recovery Kit) -> Your data (export, import) -> About -> Danger
zone (delete account, erase phone). Founder found D-174's blue "generic".
**RECOVERY KIT (founder, 2026-09-26 evening):** no kit at first launch; it moves to SIGN-IN (after Apple/Google confirms:
the 12 words + confirm three, before sync). FOUND: nothing derives a key from the kit or restores from it (no restore
path exists; a new phone gets data only by approval from an existing device, D-124), and without an account it can
restore nothing. Until the restore unit, the kit copy must not claim "the only way back in". RESTORE UNIT (kit wraps
the account's sync key; "I have a Recovery Kit" on a new phone) comes after D-178, before any public release.
D-176 SHIPPED (this commit; 0.2.0; gate 20/20 in 23m28s; sabotage 6/6 after one corrected prediction). **THE FOUNDER CAN NOW SHIP 0.2.0**: docs/RELEASE-INTERNAL-TESTING.md §5 (5.1 TestFlight, 5.2 Play, 5.3 Google fix: add the PREVIOUS app-signing key's SHA-1 as an Android OAuth client + four SHA-256s in assetlinks.json; About -> App signature shows the installed cert).
**NEXT (after compaction): D-177** five-tab shell: Balance (Lucide wallet) · Groups (users) · raised + (plus, opens Add as a SHEET, fresh each time, save -> Balance) · Expenses (receipt) · Settle up (circle-check). Lucide paths VERBATIM in .drafts/d175/lucide.md (all ISC, not Feather); NOTICE + scripts/licences.py + the D-128 glyph pin test must list the new drawings. iOS: custom glass tab bar over TabView (.toolbar(.hidden, for: .tabBar)), selected tab POPS (spring scale + accent pill), + is a 64pt raised accent circle; Android: custom Row bar + raised FAB. Groups tab: New group / Join a group on top, a glass card per group (tone tile from GroupTone = first two hex of group id mod 6: coral, violet, teal, amber, sky, pink; letter-on-fill contrast tested), N people · currency; tap selects the group for now. Then D-178 all-groups Balance (one total per currency; core FFI for sums) + group page; D-179 Add sheet + Expenses across groups + Settle up; then the Recovery Kit RESTORE unit; then web. Canvas mockups: https://claude.ai/artifact/Ajd2wQY19v1RcKDDD6xjBi (palette there is D-174 blue; Aurora supersedes).
D-175 SHIPPED (kit at sign-in; gate 20/20 in 11m27s, sabotage 4/4). NEXT: D-176 Aurora (values measured in .drafts/d176/aurora.py; hero gradient #3A2A9E->#5B3FD9->#7A3AB8->#A8327E, white text >= 6.1:1) + glass + Settings reorder + About signing-cert line + 0.2.0 (iOS MARKETING_VERSION 0.2.0 build 2; Android versionCode 2000).
PLAN: D-175 kit at sign-in (both shells); D-176 Aurora tokens + glass + Settings reorder + About signing-cert line
(Android) + version 0.2.0 -> release
guide (docs/RELEASE-INTERNAL-TESTING.md "Shipping an update" section) -> founder ships 0.2.0. Then D-176 five-tab shell
(raised popping +, glass bar, Groups tab), D-177 all-groups Balance + group page (core stats FFI), D-178 Add sheet +
Expenses across groups + Settle up. Then web.

**LATER (founder ideas, 2026-09-26; ask him before building):** user-selectable THEMES: (1) "Vardr Labs" = the pre-D-174
steel palette (ADR-0023 colours, not the old UI); (2) "Norse" = pure Nordic: black, gold and silver, runes and Norse
ornament, aesthetic not kitsch; (3) Aurora (default). Onboarding tutorial (after the redesign, when he says).
Email-code sign-in returns once the relay has an email sender (D-173 flag).

## NOW — 2026-09-26 (older) — founder's bug list + design overhaul, BEFORE the web build

The founder tested the store builds on real phones (2026-09-26) and put the web build AFTER this list:
1. Apple sign-in works but the Sign in screen stays open after success; want a confirmation, and Settings > Account
   to show HOW you signed in (Apple logo / Google G / the email). Android Google sign-in: account sheet opens, picking
   an account (a Play-console tester email) just closes it, no message. 2. iOS: phone in dark mode, app in light
   (System setting). 3. No "Join a group" in the empty state (both shells). 4+6+8. DESIGN OVERHAUL: app reads as
   engineering software; wants research (psychology, onboarding-free intuitiveness), more vibrant palette, 5-tab bar
   with a raised + in the middle, a Groups tab (create, join, overview + stats), Balance = ALL groups not the selected
   one. Tutorial later ("I will tell you when"). 5. iOS Add keeps the last saved expense. 7. New group: added person
   rows cannot be removed.
ROOT CAUSES FOUND (before fixing): (2) Appearance.apply sets window.traitOverrides.userInterfaceStyle = .unspecified
for System, which OVERRIDES the phone's style (to be confirmed on the simulator); (5) the Add tab root keeps its
@State across tab switches and save never resets it; (7) no remove control exists on either shell; (1-Google) a
Credential Manager config rejection (package/SHA-1 not registered, or the account not an OAuth TEST USER while the
consent screen is in Testing: Play testers are NOT OAuth test users) comes back as GetCredentialCancellationException,
which GoogleCredential.kt maps to CeremonyCancelled = silent. Logcat says "not registered to use OAuth2.0" in that case.
PLAN: D-172 bug fixes (2, 5, 7, 3, Google failure surfaced); D-173 sign-in completion + method shown (1); then the design
research + proposal to the founder (forks via AskUserQuestion), then the overhaul in units.
FOUNDER FORK (2026-09-26): "Join a group" ALWAYS shown (Recommended); without an account the join screen explains
"Joining needs an account" + a secondary Sign in (returns to Join). D-159 had hidden it.
D-172 BUILT (one unit, all of the above incl. sign-in, 2026-09-26 ~12:45), NOT YET GATED: iOS Appearance.apply(_:to:)
removes the override for System (test); HomeShell addForm .id bump; PeopleDraft (both, tests) with x per row;
SignInMethod (kv "account/method": apple|google|email:<addr>) stored by LedgerSync.signIn(session, method:) only on
success (tests both); SignedInConfirmation + AccountSignIn.complete (both); Settings method row with Apple logo /
Google G (new GoogleMark imageset iOS + play drawables google_mark, Google bundle "Show text=No, Pill" art, direct none);
Android signIn(bootstrapNow=false)+bootstrap() so confirmation does not wait on network, and the home route now AWAITS
the save (old: Settings opened before the session was stored); Android GoogleCredential.failureFor: cancellation ->
"Google closed without signing you in." + logcat line (flavour tests 3 each); Join everywhere (empty state + group list,
both), route "join" + "signin/join" (HomeRoutes test). Proofs: --signed-in-proof apple|google|email:x (both shells).
LOOKED: iOS 27 sim dark (before light, after dark), empty state Join, join-needs-account, confirmation Google (dark) and
Apple (light), Settings G row, people x add/remove, Add fresh after save; Android: confirmation, Settings G row, empty
state, join-needs-account, sign-in->Back->Join, people x. Artifacts ios/artifacts/d172-*.png (10), android (6).
FOUND WHILE LOOKING (not fixed, for the founder): (a) the relay has NO email sender (D-149 (c)): "Send a code" can never
deliver a code in production; the email path is a dead end for real users. (b) iOS onboarding "Confirm three words":
with the simulator tool's typing, the first Confirm with the right words fails and the second passes (3/3 runs); the
keyboard's suggestion bar showed stale words, so likely the tool's injection, unproven on a device: ask the founder.
(c) "1 people" in the group list (plural).
**D-172 SHIPPED (this commit), 2026-09-26.** Sabotage 10/10 first run; gate 20/20 on the 3rd run (1st stopped to add the
G mark to NOTICE/Licences; 2nd FAILED 2 legs: the renamed Licences entry "Sign in with Google button and G" is pinned by
AboutTests/AboutTest, updated). iOS 241, JVM 255x2, cargo 413. No core/relay change: no deploy.
**GATES ARE SLOW AGAIN:** Claude Code updated to 2.1.281 (inner app ~/Library/Application Support/Claude/claude-code/
2.1.281/claude.app), outside the Developer Tools exemption; founder asked to add it (clippy 603 s, syspolicyd 45%).
**REDESIGN, founder decisions 2026-09-26 (after the canvas https://claude.ai/artifact/Ajd2wQY19v1RcKDDD6xjBi):** the
proposal's look; Balance totals one per currency, never converted; hide the email code (and passkey) until a sender
exists; onboarding first-Confirm = simulator artefact (his phone worked). Colour refinement: get-back #0A7C74 / dark
#4CC9BA, owe #C2410C / dark #FF916B (CVD evidence .drafts/redesign/cvd.py). Drafts: .drafts/redesign/{DECISIONS.md,
tokens-v2.md,gen.py}. PLAN: D-173 hide email path (apply scripts ready: .drafts/d173/apply_{ios,android}.py, dry-run
clean; direct flavour then has no sign-in method: shows "Signing in is not available in this version of SKIPTI yet.");
D-174 tokens v2 + type + buttons/cards + ADR-0038 + SPEC §11 note; D-175 five-tab shell with raised + and the Groups tab;
D-176 all-groups Balance + group page with stats (money sums in CORE, new FFI); D-177 Add sheet, Expenses across groups,
Settle up. Tutorial: later, when the founder says.
**D-173 SHIPPED (this commit), 2026-09-26:** email code + passkey hidden (EmailSignIn flag, both shells); direct flavour
says sign-in is unavailable. Gate 20/20 on the 3rd run: run 1 and 2 failed the licence leg because cargo's and then
Gradle's cache clean-ups deleted files licences.py reads (spdx sources -> archive fallback; a POM -> re-fetched with
--refresh-dependencies); CLAUDE.md §7 records both plus the Claude-update exemption gotcha. Sabotage 2/2.
D-174 BUILT + SABOTAGE 7/7 2026-09-26 (found: iOS never drew a Space Grotesk weight, string 'wght' key; fixed + tested) — gate next (tokens v2 + type + buttons + ADR-0038 draft at .drafts/d174/0038-friendlier-design-language.md; token
values + measured contrast .drafts/d174/tokens.py; plan: new TypeRole.mono for kit words/six words, numeric -> Space
Grotesk tabular, microLabel sentence case 13/600 textDim no tracking, ~137 label sites + ~25 uppercased() calls).

## NOW — 2026-09-25 late (READ THIS FIRST; everything below it is older detail)

**State.** `main` is clean and pushed through D-171 (`fe1435d` + this WORKLOG). Every unit gated 20/20, recorded in
BUILD-LOG. Nothing running (relay and emulator closed). Production relay `4cf79fd`, current: no deploy is owed (the
relay uses only `lykill::random_bytes` from core, unchanged). **Backups now run on the host** (D-170: nightly 04:17 UTC,
14 kept; first production drill 4/4, logged in DEPLOY-ORACLE §10.4).

**Release status (2026-09-26):** iOS 0.1.0 (1) uploaded to App Store Connect (TestFlight internal); Android 0.1.0
(versionCode 1000) published to Play **internal testing** (two harmless warnings: no deobfuscation file, no native symbols).
**NEXT SESSION = THE WEB BUILD** (founder, 2026-09-26: "we will do the web build tomorrow"), see the NEXT BIG WORK paragraph.

**Earlier (2026-09-25):** iOS 0.1.0 (1) archived and uploaded to App Store Connect for **TestFlight
internal testing only** (no App Review, no public listing). Export compliance: told him to choose "Standard encryption
algorithms instead of, or in addition to, Apple's" (the Rust core's SQLCipher/AES-GCM/X25519/HKDF/Ed25519); the
exemption follow-ups are his (counsel). If he reports his answers, add the matching `ITSAppUsesNonExemptEncryption` to
Info.plist as a small gated unit. Android Play internal testing not yet reported. Runbook: docs/RELEASE-INTERNAL-TESTING.md.

**Xcode keeps rewriting `ios/Skipti/Info.plist`** when the target's Info tab is open (comments stripped, keys sorted, values
identical: checked twice on 2026-09-25 and restored). If it recurs, compare values (plutil → JSON) before restoring; never
commit a rewrite blind.

**Open items that are the founder's:** (1) Developer Tools exemption OFF before signed builds (CLAUDE.md §13; unconfirmed);
(2) ~~app-signing SHA-256 into assetlinks.json~~ **DONE 2026-09-26 and verified by Claude** (live file: com.vardrlabs.skipti
carries the upload AND the Play app-signing fingerprints, .direct the upload one; Google's statements:list returns 3
statements, no errors; the fingerprint itself is kept out of the repo, as DEPLOY-ORACLE §8.1 says). The Android OAuth client
with the app-signing SHA-1 WAS CREATED (founder, 2026-09-26), but Google sign-in on the Play install FAILED right after;
Google says a new client can take 5 minutes to a few hours. This is Android's FIRST completed Google sign-in attempt ever
(D-138 never completed one). If it still fails: get the exact in-app message ("This device could not show Google's
sign-in." = GetCredentialException, e.g. developer-console mismatch; "No Google account is available on this device." =
NoCredentialException) and check (1) the Android client id starts 403030719865- (same project as the web client), (2)
SHA-1 = APP SIGNING key (not upload) + package com.vardrlabs.skipti, (3) Google Auth Platform → Audience: publishing
status Testing = only listed test users can sign in (add them, or Publish: openid-only needs no review), (4) Play install,
not a local build; then logcat over adb for Google's code. The relay checks only aud = web client id (google.rs), no azp. (3) hosted privacy policy (D-146 (b)); (4) Oracle boot-volume backup policy (D-170 (b)); (5) whether Export keeps
promising "a JSON backup to restore" (D-166 (h)): build restore, or change the copy.
(6) **France, before the public App Store launch:** export compliance asks whether the app is distributed in France (its
own encryption-import rules, ANSSI declaration). For the internal TestFlight build, Claude suggested "No" (not on the
App Store anywhere yet). Before public release: file the ANSSI declaration (counsel) or untick France in Availability.

**Claude's follow-ups (none blocks internal testing):** Swift warning clean-up (Swift 6 risks: `Thread.isMainThread` in
async, `LAContext` in a `@Sendable` closure, unused `try`/results; D-171 (b)); Xcode "recommended settings" applied as a
gated unit (D-171 (c)); iPad support (D-171 (a), SPEC §14 matrix); core check that an expense's group exists and its payer
is a member (D-166 (i)); import undo (SPEC §9.5); a gate check that deploy scripts run under the host's shell (D-170 (a)). Play's two upload warnings (2026-09-25, first
internal release, versionCode 1000): no deobfuscation file (R8 is off, so none exists: nothing to do) and no native debug
symbols for the Rust .so: add them (ndk debugSymbolLevel, or a symbols zip from the unstripped libs) so Play vitals
can read native crash stacks; SKIPTI has no crash reporter of its own (GUARANTEES #1).

**NEXT BIG WORK (the founder, 2026-09-25): the web version.** The PWA in `web/` is unbuilt: `web/fonts/` (three woff2 + README) and an EMPTY `web/src/`.
Start by loading state, then read SPEC §10 (client architecture) and §10.2's web notes by range, every ADR that mentions
web/wasm/PWA/IndexedDB, and the wasm32 gate leg (core already builds for wasm32; `ffi.rs` gates the native store out of
wasm, so the web brings its own IndexedDB store per SPEC §10.2). GUARANTEES #6 applies with full force on the web (no CDN,
no third-party fonts or scripts). D-148 (a): `web/fonts/` still holds renamed-Plex subsets that must become IBM's own woff2
files before any web build ships. Put genuine forks (framework choice, hosting origin, service-worker scope, what ships in
v1 web) to the founder with a recommendation before building.

---

## NOW — 2026-09-21 late (HAND-OFF REPORT: read this first, then the earlier notes below)

**D-157 SHIPPED (code `641b6e0`, record committed with this WORKLOG) — 2026-09-24.** Gate on the committed tree
20/20 with a local relay (iOS 195/0 skipped; cargo 400; JVM 207×2); sabotage 11/11 first time.
**D-158 SHIPPED (code `34f0647`, record committed with this WORKLOG) — 2026-09-24.** Gate on the committed tree
20/20 with a local relay (iOS 200/0 skipped; cargo 406; JVM 212x2); sabotage 14/14 first time.
**D-159 SHIPPED (code `ef2878a`, record committed with this WORKLOG) — 2026-09-25.** Gate 20/20 with a local relay
(iOS 205/0 skipped; cargo 410; JVM 215x2); sabotage 12/12 first time. **§8.2 DEPLOYED by the founder 2026-09-25 and verified:**
/join 200 with the CSP (served bytes = deploy/join/index.html), AASA has applinks (application/json), assetlinks has
handle_all_urls and Google's validator lists both packages; relay still 4cf79fd (no relay change since). Owed later:
the Play app-signing fingerprint in assetlinks after the first Play upload. Relay deploys from now: deploy/ship-relay.sh.
**D-160 STARTED (2026-09-25, after compaction). DESIGN as decided (no founder fork: ADR-0037 already decided the shape):**
inviter = People screen section "FROM YOUR OTHER GROUPS" (account only; people whose CLAIM is in another group, not
yours, not already claimed here; dedupe by you_id, devices unioned): tap "Add Rahul" (or "Send Rahul an invite in
SKIPTI" when an unclaimed same-named member exists, case-insensitive like core's clash rule) -> member added ->
createInvite -> ffiInviteSealForDevice per device -> POST /v1/inbox ONE DEVICE PER POST (a stale device 401s the
whole post); outcome: sent to >=1 / all 429 -> full / any 401 -> notShared / else offline. invitee = GroupSync.syncAll
ends with pollInbox (only when the account has a group): GET /v1/inbox, ONLY rows for THIS device (a device-less
session lists every device's rows: marking those done would delete another device's invite), open with the device's
agreement secret (fail -> done), open the invite (401 -> done; offline -> retry next time), group already local ->
done, else stored pending in kv "invite-inbox/pending"; pending rows no longer listed are dropped. Banner at the top of
HomeShell (both shells, every tab): "Tim added you to Paris." + "You are Rahul in its ledger. Accept to see it and
add to it." Accept (primary) = redeem -> done -> select group -> Balance; Not now (secondary) = hidden until next
launch (entry kept; the relay invite expires in 7 days and the next poll then marks it done). ALSO: ensureOwnClaims
adds THIS device to the account's existing claim when missing (same you id => core adds devices), so a new device
gets in-app invites.
**D-160 PROGRESS (2026-09-25 ~02:00): iOS BUILT + GREEN** (GroupSync: knownPeople/sameNamed/member(for:)/sendInApp/
pollInbox/accept + ensureOwnClaims adds this device; InviteJoin: copy, InviteInbox (@MainActor, shared), InviteBanner,
InAppProofScreen `--in-app-proof [Group]`, InboxProofScreen `--inbox-proof`; HomeShell banner via safeAreaInset top +
.id(generation); PeopleScreen "FROM YOUR OTHER GROUPS" (KnownRow)). iOS 212/0 skipped/0 with relay (+7: InviteJoinTests
3, GroupSyncTests 4 incl. live three-account). Relay running from .drafts/d160/relay. NEXT: Android twin, then look
(cross-platform both directions), sabotage, gate, record.
**D-160 PROGRESS (02:05): ANDROID BUILT + GREEN**: JVM 222x2 = 444, 0 failed, 0 skipped (+7 twins: InviteJoinTest 3,
GroupSyncTest 4 incl. live). Android: GroupSync twin (memberFor = iOS member(for:)), InviteInbox (MutableStateFlow),
InviteBanner, InAppProof `--in-app-proof [Group]`, AppRoot InboxProof `--inbox-proof`, HomeShell banner after AccountBar
+ key(generation), PeopleScreen known rows. NEXT: look (cross-platform both directions), sabotage, gate, record.
**D-160 LOOKED (02:08), cross-platform both directions against the local relay:** iOS Tim (--invite-proof) -> Android
Bob joins Goa (--join-proof) -> iOS --in-app-proof: "FROM YOUR OTHER GROUPS / Add Rahul / In Goa" -> tap -> Rahul added,
"Rahul is in Paris. They see your invite..." -> Android --inbox-proof: banner "Tim added you to Paris." -> Accept ->
Balance on Paris. Reverse: Android --in-app-proof Lisbon (dark) "Add Tim" -> sent -> iOS --inbox-proof banner "Bob added
you to Lisbon." -> Accept -> Balance on Lisbon. FOUND WHILE LOOKING: (1) --inbox-proof inside proofRoot's
NavigationStack nested the tab stacks, so their bars vanished -> the proof now routes outside proofRoot (SkiptiApp.root);
(2) the banner moved from a TabView-level safeAreaInset to each tab root's top inset (under the nav bar). Artifacts:
ios/artifacts/d160-*.png (4), android/artifacts/d160-*.png (4). NEXT: iOS suite again (HomeShell changed), sabotage
(.drafts/d160/), full gate with relay, record.
**D-160 SABOTAGE 16/16 first time (02:5x)** (`.drafts/d160/results_d160.json`), tree restored byte-identical. ADR-0037
refinement bullet added. Record templates drafted in `.drafts/d160/final/`. FULL GATE STARTED with the relay up
(`.drafts/d160/gate.log`); then record (no bindings change: one commit), push; no deploy needed.
**D-160 SHIPPED (committed with this WORKLOG) — 2026-09-25.** Gate 20/20 in 9m35s with a local relay (iOS 212/0
skipped; cargo 410; JVM 222x2); sabotage 16/16 first time. No core, bindings or relay change: **no deploy needed.**
**D-161 STARTED + BUILT (2026-09-25 ~03:00):** GroupSync verifiedKey/isVerified/markVerified/Peer/peers (kv
"verified/<device>" = key hex, local only); redeem marks the inviter's device verified (key came in the link);
People: UNVERIFIED (WATCH colour; new token "watch" = ADR-0023's #8a6d1f light / #E8C170 dark+night, in the Night
contrast tests) on joined members + "SIX WORDS" section -> VerifyScreen (their words per device, your words, They
match / They don't match). Tests: iOS 214/0 (+2), JVM 224x2 (+2), live assertions in the D-158 live tests.
LOOKED (02:31): fresh iOS Tim + fresh Android Bob: Bob sees "Compare six words with Tim · Verified" (from the link);
Tim sees Rahul UNVERIFIED (WATCH) + SPEC sentence; Verify Rahul shows Rahul's words = Android's own words and vice
versa; They match -> Verified, label gone. Artifacts ios/artifacts/d161-* (3), android/artifacts/d161-* (2). NOT seen
by eye: Android's "They match" button path (its Bob side was already verified). SABOTAGE 6/6 first time, tree restored.
FULL GATE STARTED (.drafts/d161/gate.log). Then record, commit.
**D-161 SHIPPED (committed with this WORKLOG) — 2026-09-25.** Gate 20/20 with a local relay (iOS 214/0 skipped; cargo 410;
JVM 224x2); sabotage 6/6 first time. No core, bindings or relay change: no deploy needed.
**D-162 SHIPPED (2026-09-25): vault never backed up (SPEC §2.3 A7, §3.7) + SPEC §4.3 "as built".** Founder asked
(2026-09-25) to research the session-storage question and take the best step for speed and security. Research: vault
key is WhenUnlockedThisDeviceOnly (iOS) / Keystore (Android), so a Keychain item for the refresh token adds nothing;
memory-only access tokens mean a refresh every launch and the relay kills the family on a replayed refresh token
(auth.rs refresh_rotation_and_family_death_on_replay) -> racing refreshes would sign people out. DECIDED: keep tokens in
the vault; SPEC §4.3 amended with the reasoning. FOUND: SPEC §3.7's backup exclusion was never built (Android manifest had
no allowBackup=false: backups default ON; iOS vault not excluded) -> built: iOS VaultBackup (isExcludedFromBackup on
Application Support/ledger, set in defaultStore + loadGate), Android allowBackup=false, fullBackupContent=false,
data_extraction_rules.xml (cloud-backup + device-transfer exclude every domain). Tests iOS 215 (VaultGateTests +1; it
clears the flag first; isExcluded reads fresh, since URL caches resource values), JVM 225x2 (LedgerStoreTest +1). SEEN:
emulator dumpsys flags no ALLOW_BACKUP, bmgr "Backup is not allowed"; iOS fresh install -> first ordinary launch sets
com_apple_backup_excludeItem on the ledger folder. Also: xcodebuild -only-testing works here. SABOTAGE run 1 2/4: the Android rows
stayed green because Gradle restored a cached pass (manifest read at run time is not a test input) -> build.gradle.kts
unit tests now declare manifest, res/xml and target/debug/libskipti_core.* as inputs; run 3 (full) 4/4, tree restored.
SHIPPED (committed with this WORKLOG): gate 20/20 in 10m17s (iOS 215, cargo 410, JVM 225x2). No deploy needed.
**D-163 SHIPPED (2026-09-25): app lock (SPEC §2.3 A6, §3.7), twins.** New files ios/Skipti/App/AppLock.swift
(pbxproj A10124/B10139), ios/SkiptiTests/AppLockTests.swift (A10125/B10140), android/.../AppLock.kt, AppLockTest.kt.
LockTimeout (Immediately/1/2 default/5/15 min/Off; unknown -> default), AppLockRules (lockedAtLaunch; locksOnReturn with
>= limit, clock-back locks), AppLock state (iOS UserDefaults "applock.timeout"; Android prefs "skipti-applock"), prompt =
device owner auth (iOS .deviceOwnerAuthentication; Android BIOMETRIC_STRONG|DEVICE_CREDENTIAL, API 30+ else unavailable),
lock set BEFORE first render only when onboarding already complete; iOS PrivacyCover when not active; Android
setRecentsScreenshotEnabled(false) API 33+; Settings "App lock" (Off asks to confirm). Tests iOS 219 (+4), JVM 229x2
(+4). SEEN iOS (simulated Face ID enrolled then restored): lock at relaunch with Face ID prompt, match -> Balance,
"Immediately" + Home + return -> relocks, no-match + Cancel -> "Not unlocked. Try again.", Off confirm alert, app switcher
shows only the SKIPTI cover. SEEN Android (test PIN 1111 set then cleared; emulator closed after): lock at relaunch,
system PIN prompt (secure: screencap empty), Back -> lock screen with failure text, PIN -> Balance; Recents: the stored
thumbnail is blank, but opening Recents from inside SKIPTI shows Launcher's LIVE tile (residue). Artifacts
ios/artifacts/d163-* (4), android/artifacts/d163-* (2). SABOTAGE 8/8 first time, tree restored. Relay started for the gate (close after). GATE 20/20 in 11m04s
(iOS 219/0 skipped, cargo 410, JVM 229x2). SHIPPED (committed with this WORKLOG). Relay closed again. No deploy.
**D-164 SHIPPED (2026-09-25): the rest of SPEC §3.7.** Founder decided (AskUserQuestion, 2026-09-25): screenshot
blocking = a SETTING, OFF by default (sharing balances + bug reports keep working). Built: Android ScreenPrivacy (prefs
"skipti-privacy"/"block-capture"; FLAG_SECURE on the window, applied in onCreate before the first frame and on toggle);
iOS cannot block screenshots -> "Hide during screen recording" (UserDefaults "privacy.hide-when-captured"; CaptureWatch on
UIScreen.capturedDidChangeNotification; PrivacyCover while captured) with copy saying iPhone cannot block screenshots;
root/jailbreak WARN in Settings -> Privacy (Android test-keys or su/magisk paths; iOS Cydia/Sileo/var/jb/... paths,
never on a simulator); invite-link copy: iOS local-only + expires in 2 min; Android ClipData IS_SENSITIVE. Tests iOS 222
(+3 PrivacyTests), JVM 231x2 (+2 PrivacyTest). SEEN: Android Settings Privacy + rooted warning (emulator is test-keys),
toggle on -> window fl=SECURE and screencap 0 bytes, persists across relaunch, off -> 191 KB; iOS Settings Privacy section.
NOT SEEN: iOS cover during an actual screen recording (simulator). Emulator closed. SABOTAGE 5/5 first time; SPEC §3.7 "As built"
paragraph added. GATE 20/20 in 10m23s (iOS 222/0 skipped, cargo 410, JVM 231x2). SHIPPED (committed with this WORKLOG).
Relay closed. No deploy.
**D-165 STARTED (2026-09-25): default currency + SPEC §5.2's full ISO 4217 picker.** Found: both shells offer only the
nine promoted codes (SPEC §5.2 says full ISO 4217, nine promoted); new groups hard-code INR, Add starts in USD. Decided (no
fork, obvious default): core export ffi_currency_codes() (core's own accepted list, sorted); per-device setting "Default
currency" (iOS UserDefaults money.default-currency; Android prefs skipti-money/default-currency), unset = the phone region's
currency if core accepts it, else USD; it pre-selects New group's Settles in and a fresh Add form without a group; chips =
promoted + default + current, plus "All currencies" -> searchable list (code + platform name). Bindings regenerate: two commits.
**D-165 BUILT + LOOKED (14:20):** core all_codes()/ffi_currency_codes (164 codes, count re-derived in Python), bindings
regenerated; iOS CurrencyChoice.swift (pbxproj A10126/B10141, tests A10127/B10142: 4 tests), Android CurrencyChoice.kt +
CurrencyChoiceTest (4). iOS suite 226/10 skipped (relay down)/0. FOUND WHILE LOOKING: (1) iOS `.searchable` + dismiss left the
search bar and keyboard floating over Settings; closing search first stopped the pop -> plain search field in the list;
(2) a menu Picker outside a Form shows only its value: Default currency AND D-163's App lock row ("Off" alone) now have a
label beside them ("Lock SKIPTI"); (3) Android chips on Add (currency, split, payer, itemised) and New group drew Tok.text
on the accent: 2.44:1 light, 1.91 dark, 1.61 Night -> one chipColors(dark) in Tokens.kt (page colour on accent, 5.99/8.60/
9.40), AppearanceTest pins that only Tokens.kt builds chip colours; build.gradle.kts declares src/main/java as a test input.
SEEN: iOS Settings/list/search 'krona' -> ISK+SEK/New group ISK/Add CHF, light+dark; Android Settings ISK persists across
restart, search, New group ISK, 1500 ISK expense stored and read back, chips light+dark. Emulator closed; iOS test prefs
removed (defaults delete missed the dotted key; PlistBuddy did it). NEXT: sabotage (.drafts/d165), gate with relay, record.
**D-165 SHIPPED (code `55fe73a`, record committed with this WORKLOG) — 2026-09-25.** Sabotage 9/9 first time; first
commit refused by the hook on cargo fmt (two test lines wrapped, fixed); gate on the committed tree 20/20 in 17m21s with
a local relay (iOS 226/0 skipped, cargo 412, JVM 236x2). Relay closed. No relay change: **no deploy needed.**
**D-166 NEXT: the import screen (SPEC §9.5), both apps.** Read so far: core has ffi_plan_import (profiles SplitwiseGroup
{date_format, members: [MemberBinding name->participant id], group} and BudgetBakersWallet {self_participant, group}) and
ffi_apply_import_plan over a LedgerSnapshot; planning a Splitwise sheet with NO bindings fails with
PortError::UnmappedMembers(names) = the sheet's person columns (no new FFI needed to learn them). iOS has ImportReviewModel
(profile-blind confirm UI) + ImportProofScreen; Android ImportScreen is a verification surface with raw id fields. Missing:
entry points (Balance's empty state already SAYS 'or import from another app' with no button; Settings -> Your data),
file picking (iOS .fileImporter, Android OpenDocument), source choice, Splitwise names -> new group's people + 'which one is
you', and writing applied expenses through LedgerStore.appendExpense (records + HLC) instead of a snapshot.
**D-166 STARTED — DESIGN (decided; SPEC §9.5 already decided importers + dry run):** entry = Balance empty state
(secondary 'Import from another app' under Create a group) + Settings -> Your data card. Flow: FROM (Splitwise group CSV |
Wallet by BudgetBakers CSV) -> Choose file (iOS .fileImporter; Android OpenDocument; <=5 MB, UTF-8, BOM stripped) -> NEW
GROUP: name (from file name), Settles in (CurrencyChips; default = the currency most rows use, from a provisional plan with
placeholder ids; none -> DefaultCurrency), Splitwise: PEOPLE IN THIS FILE (from PortError.UnmappedMembers of a no-binding
plan) + 'Which one is you?' (pre-picked when it matches your name, case-blind) -> Review: the group is built with
ffiCreateNamedGroup but NOT saved; plan against snapshot + that PortGroup; existing ImportReview UI -> Apply:
ffiApplyImportPlan validates; THEN set your name if unset (= the chosen column), save+select the group, append the
included expenses as records in ONE vault session (LedgerStore.appendExpenses) -> done: 'Show the group' bumps HomeShell's
generation. BudgetBakers: same, no people step, group with only you. FOUND: Android ImportScreen.writeSnapshot writes kv
only (no records) so a projection rebuild would drop imports (verification surface only; the new flow uses records).
FOUND: Settings' Export card promises 'a JSON backup to restore' and nothing restores one: follow-up after the loose ends.
**D-166 PROGRESS (15:30): iOS flow BUILT (ImportFlow.swift A10128/B10143, ImportFlowTests A10129/B10144: 7 tests;
LedgerStore.appendExpenses; GroupStore.adopt; entry on Balance empty state + Settings Your data; HomeShell reloads on
.skiptiLedgerImported). LOOKING found: (1) **CORE BUG since D-69: Splitwise signs reversed.** Splitwise's KB ('How can I
double check my balances?'): 'When they pay for an expense ... the number is positive. When they borrow ... negative.'
kennethxu/splitwise2spliit agrees. Core read negative = paid, so every imported payer was wrong. FIXED in splitwise.rs
(v > 0 payer; others owe -v); every fixture row's member cells negated by .drafts/d166/flip_fixtures.py (46 rows in
splitwise.rs, ledger_port.rs, ImportReviewTests, LedgerPortTests, ImportProofScreen, Android ImportReview.kt) so all
old tests keep their meaning (cargo 249 green); new golden the_positive_cell_is_the_payer_as_splitwise_documents (hand
from the KB sentence). D-69's row needs a Corrected D-166 note. (2) refusals printed participant ids -> names;
(3) review date showed 19 Sep for a 20 Sep row (UTC midnight shown in US time) -> review formats UTC, saved expenses get
ImportSetup.localWallTime (date-only -> local noon; wall time -> same local wall time); (4) 'Import 1 expenses'; system
section headers -> microLabel. iOS ImportFlow/ImportReview/LedgerPort tests 20/20. NEXT: finish the look (apply), Android.
**D-166 PROGRESS (15:50): both apps BUILT + LOOKED end to end** (iOS sim + Android emulator: Tahoe sheet -> review ->
leave out duplicate -> 3 imported -> $35 owed, Asha +15, Rahul +20 = hand-worked; Android re-import flags all 4 as
duplicates). More found: iOS review Toggle took no taps + Android review never redrew -> whole row is the switch;
group name drops dates/'export'; per-group empty copy now 'Add your first expense.'; assemblePlayDebug alone ships the
OLD Rust .so (make android-core; CLAUDE.md). D-69 row corrected in place; SPEC §9.5 as-built. Baseline iOS 233/10
skipped (relay down)/0, JVM green. Sabotage runner .drafts/d166 (9 rows, C1 superset). Record drafts .drafts/d166/final.
NEXT: sabotage -> full gate with relay (no bindings change: one code commit after the gate) -> record.
**D-166 SABOTAGE: run 1 5/9** (I1/A1 green: core's merge check ignores whether a group exists / payer is a member;
I4/A4 green: the test counted ALL records and rebuild's legacy backfill re-mints) -> tests strengthened (plan ledger has
the group; exactly 2 expense-type records before rebuild) -> run 2 of those rows 4/4. Combined .drafts/d166/
results_combined.json. NEXT: full gate with relay, then commit code + record (no bindings change).
**D-166 SHIPPED (committed with this WORKLOG) — 2026-09-25.** Gate 20/20 in 14m08s with a local relay (iOS 233/0
skipped, cargo 413, JVM 243x2); sabotage 9/9 over two runs (run 1 5/9, recorded). Core changed (Splitwise reader) but
no FFI: bindings unchanged. The relay is unchanged: **no deploy needed** (the apps carry the core fix).
**D-167 NEXT: Android Recovery Kit Save PDF + Print.** Drafts ready: .drafts/d167/KitPdf.kt (pure layout + PdfDocument
render + PrintManager adapter; iOS page geometry and warning paragraph word for word; bytes in memory only; Save via
CreateDocument, the person picks where) and .drafts/d167/KitPdfTest.kt. To see it on the emulator: test PIN + emulated
fingerprint (as D-147/D-163, removed after), Reveal, Save PDF to Downloads, pull and read it, Print preview.
**D-167 BUILT + LOOKED (16:10):** KitPdf.kt + KitPdfCopy + Save PDF/Print on KitRevealScreen; KitPdfTest 4/4.
Emulator (test PIN 1111 + emulated fingerprint, both cleared after; emulator closed): Reveal -> Save PDF -> system
'Downloads' picker -> saved skipti-recovery-kit-2026-09-25.pdf (160 KB) -> pulled: text extracts, words = the revealed
12, two columns, iOS paragraph verbatim; Print -> system dialog 'Page 1 of 1', Letter; cancel writes nothing. FOUND:
the 'Saved.' line sat below the fold -> the page scrolls to it. NEXT: sabotage, gate, record.
**D-167 SHIPPED (committed with this WORKLOG) — 2026-09-25.** Gate 20/20 in 8m49s with a local relay (iOS 233/0
skipped, cargo 413, JVM 247x2); sabotage 4/4 first time. Android only: no deploy. Pulled test PDF deleted from drafts.
**D-168 NEXT: a different account on the same phone (D-155 residue (a)) -> founder fork, asked 2026-09-25.**
**D-168 DECIDED by the founder (AskUserQuestion, 2026-09-25): ONE ACCOUNT PER PHONE.** Design: vault kv
'account/owner' = the first signed-in subjectId (kept on sign-out; backfilled from a restored session); a sign-in by a
different subject is refused BEFORE the session is stored or anything syncs (LedgerSync.signIn -> .otherAccount), the
refused session is revoked on the relay (AccountSignOut.endSession), and the person sees why, with Export first and a
confirmed 'Erase this phone's ledger' (vault file + keychain/keystore key; then setup again). The same erase in Settings
-> Your data (selling / handing on a phone). Never automatic. Twins.
**D-168 BUILT + LOOKED (19:00):** iOS AccountOwner.swift (A10130/B10145; tests A10131/B10146, 4/4), Android
AccountOwner.kt + AccountOwnerTest (4/4). LedgerSync.signIn -> outcome (owner check first; restoreSession backfills the
owner); iOS SignInScreen shows OtherAccountNotice on refusal; Android routes to other-account and no longer pushes
before the check (AppRoot fired fireAndForget with the new session unconditionally). Erase: vault files + key, back to
setup; Settings card after the Recovery Kit on both. Proof --other-account-proof (both). FOUND: the relay already 401s a
dev mint for a 2nd account declaring the same sync device (real sign-ins declare none); ProofSession.signIn claimed
success even when refused -> now honest; a rerun proved the owner survives (the fresh 'owner' mint was refused). SEEN
both: refusal notice; erase confirm; erase -> setup with new words. NEXT: sabotage, gate, record.
**D-168 SHIPPED (committed with this WORKLOG) — 2026-09-25.** Gate 20/20 in 9m07s with a local relay (iOS 237/0
skipped, cargo 413, JVM 251x2); sabotage 8/8 first time. No core/relay change: no deploy.
**D-169 NEXT (release blocker found while preparing the release guide): iOS Info.plist has NO
NSFaceIDUsageDescription**, so on a real Face ID iPhone the app lock and the Recovery Kit re-view (biometrics-only) may
fail; the simulator does not enforce it. Fix + a test that the built Info.plist carries it. Then the release-readiness
report (export compliance question = founder/counsel; no hosted privacy policy yet, D-146 (b)).
**D-169 SHIPPED (committed with this WORKLOG) — 2026-09-25.** NSFaceIDUsageDescription added + hosted test; gate
20/20 in 10m22s (iOS 238/0 skipped); sabotage 1/1. Relay closed. No deploy.
**NEXT: docs/RELEASE-INTERNAL-TESTING.md (the founder's step-by-step TestFlight + Play internal testing guide), then the
release-readiness report.** Open for the founder: export compliance answer; hosted privacy policy (D-146 (b)); the
'JSON backup to restore' promise with no restore (D-166 (h)); Developer Tools exemption OFF before any signed build.
**RELEASE GUIDE WRITTEN (2026-09-25): docs/RELEASE-INTERNAL-TESTING.md** (no private values: keystore path, alias
and fingerprints are placeholders, as DEPLOY-ORACLE does). Loose ends from this NOW block are DONE (D-165..D-168) plus
D-169. **OPEN ITEMS FOR THE FOUNDER:** (1) Developer Tools exemption OFF before any signed build (until he confirms);
(2) after the first Play upload: app-signing SHA-256 into assetlinks.json and an Android OAuth client with its SHA-1
(guide §2.4); (3) hosted privacy policy (D-146 (b)); (4) export compliance answer; (5) JSON restore promise (D-166 (h)).
**NEXT for Claude:** follow-ups not blocking internal testing: JSON backup restore (or honest Export copy), core check
that an expense's group exists and its payer is a member (D-166 (i)), import undo (SPEC §9.5), web fonts (lowest).
**SERVER CHECK (2026-09-25, founder ran the read-only block):** relay active, no errors in 24 h, 38 GB free; live
/version 4cf79fd; no server/Cargo.lock change since and core's only relay use (lykill::random_bytes) unchanged -> no
deploy needed; AASA, assetlinks (upload FP only, as expected pre-Play) and /join served and = repo. **FOUND: the backup
timer was NEVER installed (0 timers; /var/backups/skipti absent): ADR-0030 layer 1 has not run in production, and no
restore drill since the D-156 v5 migration.** Handed: apt sqlite3, install from ~/skipti (deployed tree = repo scripts),
first backup, restore drill. Waiting for his output; then record it (and Oracle volume backup policy is his to confirm).
**D-170 SHIPPED (committed with this WORKLOG) — 2026-09-25.** Backup + drill scripts now #!/bin/bash (Ubuntu dash refused
pipefail; first production backup died on line one). Founder installed: FIRST PRODUCTION BACKUP relay-vault-20260925-
234819.db (106,496 B) + DRILL 4/4 BACKUP RESTORABLE (/version 4cf79fd); timer enabled 04:17 UTC, 14 kept. DEPLOY-ORACLE
§10.4 drill log started; D-84 corrected in place. Gate 20/20 in 8m09s (DEPLOY-ORACLE edited during it; recorded). SERVER
READY for internal testing; still his: Oracle boot-volume backup policy (layer 2).
**D-171 SHIPPED (committed with this WORKLOG) — 2026-09-25.** Founder's first Validate App: 'Invalid bundle' (iPad +
portrait-only). Founder chose iPhone only for now: TARGETED_DEVICE_FAMILY = 1 (4 configs); built Release Info.plist
UIDeviceFamily (1), portrait. Gate 20/20 in 8m26s. He re-archives (build 1 reusable: nothing uploaded). Follow-ups: iPad
unit (SPEC §14 matrix); Swift warnings clean-up (Swift 6 risks: isMainThread in async, LAContext in @Sendable);
Xcode 'recommended settings' as a gated unit.
**NEXT: the remaining loose ends** (~~default currency~~ D-165; ~~import UI~~ D-166; ~~Android kit PDF~~ D-167; ~~second account~~ D-168; Android kit Save PDF/Print; web/fonts Plex woff2 (web is
unbuilt: lowest); D-155 residue (a) signing into a different account on one device), then **the release-readiness
report to the founder** (basic build works in simulation ->
step-by-step TestFlight + Play internal testing export/sign/upload; remind him to turn OFF the Developer Tools
exemption before any signed build; after the first Play upload, the app-signing SHA-256 into assetlinks.json).
Loose ends (triage which block internal testing): web/fonts Plex woff2; Android kit Save PDF/Print; app lock;
default currency; import UI; D-155 residue (c) sessions in the vault (needs a founder decision). Local relay
and emulator were checked healthy at 02:44 and CLOSED at the founder's request after D-162 (restart when a unit needs
them: CLAUDE.md §7.2 commands; relay DB .drafts/d160/relay/relay-vault.db).
**D-161 (history) — verification states (twins).** SPEC §2 / §3.3 (read by range first): a member's devices come from their
claims (D-157/D-160); show UNVERIFIED on a joined member until the six words (the safety phrase, D-124's words from
both devices' keys) are compared side by side and confirmed with one tap; store the confirmation locally (never
relayed). Then the loose ends list and the release-readiness report to the founder.
**D-160 (history) — in-app invite (inbox + banner), twins.** Relay side exists (D-156: POST/GET /v1/inbox, done).
Core exists (ffi_invite_seal_for_device / open_from_device). Plan: when adding someone to a group (or on a member
row), "Invite in SKIPTI" lists people you share another group with (their CLAIMS carry you_id + devices + keys;
de-duplicate by you_id); pick one -> the new group gets a member named as in the other group (or the picked member
if already added) -> createInvite for that member -> seal the link to each of their devices -> POST /v1/inbox. On the
invitee: every sync GETs /v1/inbox, opens each entry with this device's agreement secret, opens the invite (relay)
-> banner inside SKIPTI "Tim added you to Paris. Accept?" (Accept = redeem; Later = keep; the entry is marked done
after accept or explicit dismiss). Then D-161 verification states (UNVERIFIED + six words, from claims' device keys).
Then the loose ends list and the release-readiness report to the founder.

**D-159 (history) — BUILT:** Core `core/src/qr.rs` (byte mode, level M, v1-6)
+ ffi_qr_encode; iOS `InviteJoin.swift` (QRImage/QRCodeView, InviteScreen, JoinPasteScreen, JoinScreen, proofs
`--invite-proof` -> People screen after a sync, `--join-proof <link>`), People INVITE section (signed in; unlinked
members), Groups "Join a group", onOpenURL sheet, applinks entitlement; Android `InviteJoin.kt` twin (Canvas QR,
ACTION_SEND, clipboard), People INVITE section, group list Join, MainActivity VIEW intent + onNewIntent (singleTop),
autoVerify intent filter; GroupSync.createInvite pushes the group first; **FOUND: nothing linked a CREATOR to their
member -> `ensureOwnClaims` (every account claims its own place; syncAll + createInvite)**; **FOUND: a stale access
token looks like "not yours" (uniform 401) -> `LedgerSync.freshSession` (probe v1/devices, rotate once, store) used
before group sync with nothing pushed, refresh(), invite, join**; proof arg bug (Android splits args; the link is now
found by ffiInviteIsLink) + a bad link now reads "not a SKIPTI invite link". Tests: iOS 205/0/0 (InviteJoinTests 4 incl.
CoreImage QR read-back for lengths 1..106 + a real link; GroupSyncTests 6); JVM 213x2 + InviteJoinTest 2. SEEN: iOS
invite QR (light), People INVITE section; CROSS-PLATFORM: iOS invites, Android (fresh account) joins as Rahul, and
again as Priya in dark mode; used link -> "already used"; Tim's iPhone then lists no one to invite. Shots:
`ios/artifacts/d159-*`, `android/artifacts/d159-*`. Deploy: `deploy/join/index.html` + DEPLOY-ORACLE §8.2 (AASA
applinks, assetlinks handle_all_urls, /join Caddy block with CSP hash). Founder asked (2026-09-25): compaction vs
new session (either; state is in WORKLOG/.drafts) and people not in the app (add by name + share link = already so).

**NEXT (history): D-159 — invite and join SCREENS (twins).** Plan: (1) core QR encoder DRAFTED at `.drafts/d159/qr.rs` (byte
mode, level M, v1-6; RS + format goldens from the published tables; verify on iOS with CoreImage's QR detector
reading back core's matrix) -> `core/src/qr.rs` + `ffi_qr_encode`; (2) People screen: an INVITE section (with an
account only; GUARANTEES #5) listing members not yet linked -> InviteScreen: makes the invite (7 days), QR drawn
from core's modules, Share link (ShareLink / ACTION_SEND), Copy; (3) Join: "Join a group" on the Groups list (paste a
link) + opened links (iOS onOpenURL, Android VIEW intent) -> JoinScreen "Tim invited you to Goa Trip as Rahul" ->
Join (redeem) / Not now; errors: used/expired, offline; without an account: plain "joining needs an account" + Sign in
(secondary); (4) founder deploy items: AASA `applinks` (/join), assetlinks `handle_all_urls`, a static /join page
(copy link + instructions), the iOS entitlement `applinks:skipti.vardrlabs.com`, Android intent-filter autoVerify.
Proof launch arg for the join screen with a link (the simulator cannot follow a universal link without AASA).

**D-158 (history) — PROGRESS (2026-09-24 22:10):** BUILT both shells + core. Core: GroupOp::GroupKey (personal) + projection
`keys`; revision `scope` (out-of-scope revisions -> rejected); **FOUND WHILE WRITING THE E2E TEST: a joiner's app
writes its own "you" id, which the other members never saw -> core `aliases` (per group: another account's claim
makes their "you" read as the claimed member; renames too); Yous gains per-group aliases; ffi_merge_you_expenses /
_settlements take `aliases` (shells' yous() now returns them).** Shells: GroupSync.swift/.kt (keys, membership row,
group-log push/pull + `refusal` rules, createInvite, redeem), hooks after account push + restore, refresh on
foreground (iOS scenePhase, Android onResume). Tests: core 242; iOS 199/0/0 WITH RELAY (GroupSyncTests 4 incl. live
two-person join: Tim reads Bob's taxi as Rahul's); JVM 211x2 0 skipped (GroupSyncTest 4). pbxproj A10120/B10135
(GroupSync.swift), A10121/B10136 (GroupSyncTests.swift). ADR-0037 refinements section + THREAT_MODEL bullet written.
Spotted (NOT fixed, pre-existing): Android HttpUrlConnectionTransport builds GET query as literal "?\${query...}"
(escaped dollar) - harmless today (no caller passes a query). NEXT: sabotage (.drafts/d158/), commit code (bindings),
gate on committed tree with relay up, record.

**NEXT: D-158 — shared groups work end to end, headless (twins). PLAN: `.drafts/d158/PLAN.md` (READ IT).** Core:
GroupOp::GroupKey (personal scope; own devices get group keys from the ACCOUNT log, never via relay-supplied sender
keys) + projection `keys`; revision records gain `scope` and apply only within their target's group. Shells: new
GroupSync (keys, membership row, group-log push/pull with STRICT acceptance: scope == G, payload group == G, no
you/key ops, not authored by this device, far-future HLC not fed to the clock), createInvite + redeem (headless;
UI is D-159), hooks after account push / restore. Live two-account test (skips without a relay).

**D-157 (history) — core: invites + claim.** BUILT: `core/src/straumr/invite.rs` (link
`https://skipti.vardrlabs.com/join#<base64url(s)>`, hand-written canonical base64url (no new dep), token/key via
hkdf_sha256(s, "skipti", "skipti/v1/invite-token"|"-key"), token_hash = sha256(token) hex, payload JSON v1 sealed
AES-GCM with random nonce + AAD "skipti/v1/invite-payload" ≤ 4096 B, inbox entry = 0x01 ‖ ephemeral X25519 pub ‖
seal(HKDF(DH, "skipti/v1/invite-inbox" ‖ eph ‖ recipient)) of s); `GroupOp::Claim {group, member, you, devices[id,
key]}` (1–16 devices), projection: first claim per member stands (VersionId order), same account re-claim adds
devices, other account → `contested`; claim of a member THIS account claimed → added to other_you_ids (so D-154's
merge makes the joiner's balances right); `ProjectedGroup.claims`. FFI: GroupOpIn::Claim, ClaimDeviceIn, ClaimOut,
GroupsProjectionOut.contested, ffi_group_key_generate, ffi_invite_new/is_link/token/open/seal_for_device/
open_from_device (the LINK string is the handle; shells never see s). Shells: claim takes group scope (Swift switch +
Kotlin when). Tests: core 236 (7 invite, 5 claim, 3 FFI; goldens `.drafts/d157/golden.py`), JVM GroupRecordsTest 11,
iOS 195/8/0. Bindings regenerated. SABOTAGE RUNNING (`.drafts/d157/`, 11 rows). Then: commit code (bindings), gate on
committed tree, record, commit.

**D-156 SHIPPED (committed with this WORKLOG) — 2026-09-24.** Gate 20/20 in 9m21s with a local relay of this tree
up (iOS 194/0 skipped/0; cargo 385; JVM 206×2 carried forward, no Android input changed); sabotage 13/13 first time.
**DEPLOYED by the founder 2026-09-24: live `/version` = `4cf79fd…` (checked).** Founder (2026-09-24): Apple + Google
Play are ORGANIZATION accounts (Vardr Labs LLC); SIWA enabled; Google OAuth web/iOS/android/android-debug exist.
He wants: finish everything on Claude's side (as many sessions as needed), THEN a step-by-step export/sign/upload
guide for TestFlight + Play internal testing.
**NEXT: D-157 CORE** (no relay, no UI): invite link `https://skipti.vardrlabs.com/join#<base64url(s)>` encode/parse
(refuse anything else; fragment only), `token = HKDF(s, "skipti/v1/invite-token")`, `key = HKDF(s,
"skipti/v1/invite-key")` (check lykill::hkdf_sha256's salt/info signature; pin goldens in Python), payload seal/open
(AES-GCM with key; payload = group id, group key, epoch, member id, member name, inviter device agreement pubkey;
versioned, bounded ≤ 4096 B), inbox entry = s sealed to a device X25519 key (reuse the envelope wrap), `claim` GroupOp
("member M is me" + the claimer's device ids and agreement pubkeys) with projection: a claimed member = that
account's "you" on the claimer's side (feeds D-154's merge), linked member for everyone else; first claim wins,
later claims of M reported. FFI + bindings (commit code first: bindings freshness vs HEAD). Then D-158 shells.
Release question (founder, 2026-09-24): answered in chat; revisit when the basic build is done.

**D-156 (history) — STARTED (2026-09-24, after compaction) — relay for shared groups.** Design as built: new module
`server/src/shared.rs` (child of lib.rs); group logs reuse BlobStore/r_logs under key "group:<G>" (colon: can
never be a 32-hex subject), push/pull share the account log's code (409 = pull and retry, as today); membership =
`reg.is_member(slot G, subject)`, uniform 401 otherwise. Invites: memory map token_hash → {group, payload,
expires} + r_invites; open = {token} → {group_id, payload, expires_ms} (group id is inside the sealed payload
the joiner cannot read yet, so open takes the token alone); admit burns the invite in ONE vault txn with the
envelope rows (vault first, memory after). Inbox: no sender stored (learn less); expiry 30 d; hourly sweep drops
expired invites/inbox rows. Lock order: deletions → invites → inbox → store → envelopes → registry → vault.
Purge: the last member's deletion drops the group's log + invites (emptied groups computed under the invites
lock, so no admit can race it); inbox rows to the deleted devices go.
**D-156 progress:** BUILT (`server/src/shared.rs`, persist v5, lib wiring, main sweep, THREAT_MODEL 3 rows + 1
bullet, DEPLOY-ORACLE v4→v5 note). Tests: `server/tests/shared_groups_http.rs` 6 + persist v4→v5 test, all green;
clippy clean. SABOTAGE 13/13 first time (`.drafts/d156/results_d156.json`), tree restored byte-identical. Real-file
migration: D-155's v4 relay vault → v5 on boot, rows intact, new routes answer (`.drafts/d156/relay/`, relay LEFT
RUNNING for the gate so iOS relay tests run). FULL GATE STARTED (`.drafts/d156/gate.log`). Then record
(`.drafts/d156/final/`), commit, push, and hand the founder the deploy commands (§7.1, backup-03e6abf).
Founder asked (2026-09-24): tell him when the basic build of the whole app works in simulation, so he can ask
for internal-testing builds (Play internal testing + TestFlight); answer the signing/process question then.

**HAND-OFF (2026-09-24, for compaction). Shipped this session: D-150 deletion UI, D-151 sync round-trip proofs,
D-152/153/154 groups + "you" as records (adopt + merge), D-155 Sign out. Tree clean after this commit.**
**ADR-0037 WRITTEN (committed with this WORKLOG): shared groups: group keys, group logs, invites.** Read it first.
**NEXT: D-156 RELAY for shared groups** (server only; founder deploys; schema v5), per ADR-0037:
- Group logs `POST/GET /v1/groups/{G}/log` (same PushEntry/PulledEntry shapes as /v1/logs; store key
  "group:<G>" in BlobStore + r_logs; auth = `reg.is_member(envelope slot of G, ctx.subject)`, uniform 401
  otherwise, non-member indistinguishable from no group). Existing /v1/logs stays subject-owned.
- Invites: `POST /v1/groups/{G}/invites` {token_hash (SHA-256 hex of token), payload (hex, opaque), expires_ms}
  member-only; `POST /v1/invites/open` {group_id, token} → {payload} (does NOT burn; uniform 401 for unknown/
  expired/redeemed); `POST /v1/groups/{G}/admit` {token, device_id, entries[recipient_device_id, blob]}: token
  valid → claim device_id for caller, EVERY recipient must be bound to the caller's subject (registry
  device_owner), upsert envelopes (membership), BURN invite (single use). New table r_invites.
- Inbox: `POST /v1/inbox` {entries[recipient_device_id, blob]}: each recipient device's subject must share an
  envelope slot with ctx.subject (else uniform 401); `GET /v1/inbox` → entries for the caller's devices (+ id);
  `POST /v1/inbox/{id}/done` removes. New table r_inbox; expiry ~30 days; bound count per recipient.
- Account deletion purge: remove the subject's inbox entries + invites it registered? (invites belong to the
  group; keep). Tests: server/tests/shared_groups_http.rs (authz negatives: non-member push/pull/invite, wrong/
  reused/expired token, admitting a device not yours, inbox to a stranger), restart persistence, migration v4→v5.
- Sabotage each guard; THREAT_MODEL line; deploy commands for the founder (DEPLOY-ORACLE §7.1, backup, v4→v5).
AFTER: D-157 core (invite link encode/parse `https://skipti.vardrlabs.com/join#…`, HKDF token/key derivation,
payload seal/open, `claim` group op + projection: claimed member = that account's you), D-158 shells group keys +
group-log sync + one-time move of group records, D-159 invite/join UI (QR = the link; no camera permission;
universal/app links need AASA applinks + assetlinks with the RELEASE cert fingerprint: founder), D-160 in-app
inbox banner, D-161 verification states (UNVERIFIED, six words). Removal + rotation later.
Developer Tools exemption still ON (internal testing): remind before any signed/store build.

**FOUNDER DECISIONS — SHARED GROUPS (2026-09-24, AskUserQuestion; the founder's own words condensed):**
1. **Invite: QR together, link apart, AND an in-app invite.** Adding someone to a group makes them PART OF THE
   GROUP AND ITS LEDGER AT ONCE, whether they accept now, later, or never ("maintain the ledger part of everyone
   being there and not skipping someone just because they didn't accept an invite, thus evading responsibility").
   Accepting only LINKS their account/devices (group key + sync). When adding someone, offer: show QR, send a
   link, and an in-app invite.
2. **Invite a named person** (the invite is bound to that member, e.g. "Rahul"; no picking names).
3. **In-app invite reaches people you already share a group with** (their account is known through that group;
   no directory, no lookup; the relay learns nothing new). New people: QR or link first.
4. **"Push" = a banner inside SKIPTI when they next open it** ("Tim added you to Goa Trip. Accept?"). NO APNs/FCM
   (GUARANTEES #6, ADR-0012).
QR-joined members start VERIFIED (the scan carries the key); link-joined start UNVERIFIED until the six words are
compared (SPEC §2 "Verify your peers"). Next: write ADR-0037 from these + SPEC §2/§3.5/§3.6 + ADR-0005, then units:
core group keys/seal (reuse lykill wrap + ffiSyncSeal?), relay group logs + invites/join requests (DEPLOY by
founder), shells group-log sync + member↔account linking, invite/join/banner UI, verification states; removal +
rotation later.

**D-155 SHIPPED (committed with this WORKLOG) — 2026-09-24.** Gate 20/20 8m44s; iOS 194/8/0; JVM 206×2;
sabotage 8/8. D-150 residue (f) closed. New residue (c): sessions live in the vault, not memory+keychain as
SPEC §4.3 says (predates this; needs a decision).
**NEXT: shared groups between people** (the biggest block). Read first: SPEC §5.3 (group key vs personal DEK),
ADR-0005 (key hierarchy, envelopes), D-123 (device approval + key wrap), relay envelope routes (D-41), D-37
(rotation on member removal). The invite shape (link / QR / code; how a person, not a device, joins) is a
FOUNDER FORK: ask before building.

**D-155 (history) — a plain Sign out, both shells.** IdentityService.revokeSession (POST
v1/auth/session/revoke {family_id}, bearer; 204 ok); AccountSignOut.endSession (401 → refresh once, store rotated,
revoke again; other failures → not confirmed, still signs out locally); Settings card (note + secondary "Sign out")
in every signed-in state. SignOutTests 5 (iOS) / SignOutTest 5×2 (JVM). SEEN on iOS 27 + API 34 with a local relay:
card → Sign out → "No account"; relay r_families.revoked = 1 for that device (revocation marks, keeps the row for
reuse detection), r_grants 0. Sabotage 8/8 (`.drafts/d155/`). FULL GATE STARTED.

**D-154 SHIPPED (code `0f17b52`, record committed with this WORKLOG) — 2026-09-24.** Gate on the committed tree
20/20 in 12m01s; cargo 378; sabotage 10/10 first time. D-153 residue (a) closed in place. "One you per account"
is now complete: adopt (D-153) + merge (D-154).
**NEXT (proposed order):** (1) plain Sign out in Settings, both shells (D-150 residue (f)); (2) shared groups
between people = the biggest block: READ SPEC §5.3 (group key vs personal DEK), ADR-0005 (key hierarchy,
envelopes), D-123 (device approval/wrapping), the relay's envelope routes (D-41: members add members,
per-recipient reads) BEFORE designing; the invite shape (link / QR / code) is a founder fork; (3) the loose
ends: web/fonts Plex woff2, Android kit Save PDF/Print, app lock, default currency, import UI.

**D-154 (history) — the merge ("one you, merge").** Core `straumr/identity.rs` (Yous: id map,
shares ADDED in the first one's place, settlement between two of your ids → None); groups projection maps
member ids (first place, your own name in that group if any); FFI ffi_merge_you_expenses (payer, shares,
added/last-edited by, money-conflict values) + ffi_merge_you_settlements (self-settlements leave the ledger:
core refuses them in balances AND export). Shells: Sync.project merges expenses (so projectedExpenses and the
kv ledger agree); rebuildProjection merges settlements. FOUND BY THE NEW TEST: the legacy backfill (once per
vault, D-141) re-minted a MERGED row as a second expense when a rebuild merged before the backfill's first
run → rebuildProjection now runs the legacy backfill FIRST (both shells). iOS 189/8/0; JVM 201×2; sabotage
10/10 first time (`.drafts/d154/`). Committing code first (bindings freshness), then gate, then record.

**D-153 SHIPPED (committed with this WORKLOG) — 2026-09-24.** Gate 20/20 with a relay up (iOS 188/0 skipped;
JVM 200×2); sabotage 14/14 after one corrected prediction (both runs kept). D-151 residue (a) closed in place.
**NEXT: D-154 the merge** (founder: "one you, merge"): other "you" ids (SelfDTO.aliases, from
GroupsProjectionOut.otherYouIds) count as the account's "you" everywhere: balances, splits, labels, settle.
Likely a core function mapping alias ids → you in the projected ledger (expenses: payer + shares, summing shares
that collapse; settlements: from/to, a settlement from you to you drops out of balances; groups: members deduped)
applied in rebuildProjection so every read sees one person; records stay untouched. Read SPEC §5.4–5.6 (splits,
remainders, simplification) first; check core balance code for self-settlement handling.

**D-153 (history) — the shells use group records.** iOS DONE (uncommitted): LedgerStore.Sync
groupOpType=3, groupOpRecord (group scope; you = personal, groupId nil; "GRP\x01"), appendGroupOps,
projectGroups, backfillGroupRecords (CONTENT-based, idempotent: kv groups w/o create + kv self w/o you record;
refused ops skipped), rebuildGroups (UNION RULE: kv groups without a create stay; unreadable kv groups THROW, never
overwritten; kv self := projection you, name falls back to this device's own, aliases = other you ids);
rebuildProjection calls it; GroupStore.save = diff → ops → rebuildGroups (`LedgerStore.operations(from:to:created:)`,
uncreated group written whole as a create); ensureSelf/setYourName write you records; namedGroups resolves "you"
per group (own id, else an alias); pushLatest backfills first; both proofs assert groups again. iOS
GroupRecordsTests 9/9 (pbxproj A10118/B10133). ANDROID TWIN DONE (GroupRecordsTest 9×2). With a relay up:
iOS 188/0 skipped/0; JVM 200×2 0 skipped. Smoke on the iOS 27 sim's pre-D-153 vault: Groups load, "Add person"
(Amit) saved through backfill + diff + rebuild. SABOTAGE 14/14 after ONE CORRECTED PREDICTION: run 1 = 12/14,
S6/A6 red set larger than predicted (GroupStore(s)Test old-fixture migration also red, both shells; the
prediction missed it, code fine) — corrected openly in the runner, re-run 2/2 OK; both runs kept
(`.drafts/d153/results_d153_run1.json`, merged `results_d153.json`). FULL GATE STARTED (relay up).

**D-152 SHIPPED (code `e5e9d05`, record committed with this WORKLOG) — 2026-09-24.** Gate on the committed
tree 20/20 in 10m40s; cargo 371; iOS 179/8/0; JVM 191×2; sabotage 8/8.
**NEXT: D-153 the shells use group records** (iOS + Android twins): GroupStore's writes (createGroup, addMember,
renameMember, setYourName/ensureSelf) append 0x03 records (group scope; "you" PERSONAL scope) through
ffiEncodeGroupOp; rebuildProjection writes ledger/groups + ledger/self from ffiProjectGroups; one-time backfill
of existing kv groups + self into records BEFORE any rebuild (D-122 #3); a fresh device adopts the account's
"you" on pull; proofs assert groups again (D-151 (a)); pending: what a device shows for a group that does not
contain the adopted "you" (the merge case, D-154).

**D-152 (history) — groups and "you" as records, CORE ONLY.** `core/src/straumr/groups.rs`:
GroupOp {create, addMember, renameMember, renameGroup, you} on record type 0x03 (old builds already pack/store/
ignore 0x03; a new type would stop their sync); "you" = PERSONAL scope. One projection: per-field LWW by
VersionId, currency fixed by the earliest create, members a union in first-written order, "you" = earliest you
record (others reported for the merge), two OTHER members with one name told apart ("Rahul (2)") so
ffi_named_group validates, unreadable → rejected, early ops → pending. FFI: GroupOpIn, ffi_encode/decode_group_op,
ffi_project_groups → GroupsProjectionOut. 16 module tests + 3 FFI tests; codec goldens re-derived in Python
(`.drafts/d152/golden.py`); sabotage 8/8 (`.drafts/d152/results_d152.json`). Bindings regenerated.
Commit code first (bindings freshness compares HEAD), then full gate on the committed tree, then record.
NEXT after: D-153 shells (GroupStore writes records; rebuild writes ledger/groups + ledger/self from records;
one-time backfill BEFORE any rebuild; proofs assert groups again).

**D-151 SHIPPED (committed with this WORKLOG) — 2026-09-24.** Gate 20/20 WITH A LOCAL RELAY UP: iOS 179/0
skipped/0; JVM 191×2, 0 skipped; cargo 352; sabotage 6/6. D-150 residue (a) closed in place.
**FOUNDER ANSWER (2026-09-24, AskUserQuestion): "One you, merge (Recommended)"** — each account has ONE
"you"; a new device adopts it; two devices that each already had their own "you" with expenses become one
person automatically (every device shows one "Tim (You)"). Build in stages: adoption first, merge after.
**PLAN:** D-152 core: group ops (RecordType::GroupOperation 0x03, reserved and unused until now) + a new
Profile record type (0x06, personal scope: who "you" are; NEVER to be shared into a group's log later) + ONE
order-independent projection (per-field LWW by VersionId; members = union, ordered by first appearance;
unknown ops skipped; "you" = the earliest profile record), FFI + bindings + goldens re-derived in Python.
D-153 shells: GroupStore writes records; rebuildProjection writes ledger/groups + ledger/self from records;
one-time backfill of kv groups/self into records BEFORE any rebuild (D-122 #3's erase trap); proofs assert
groups again (D-151 (a)). D-154 the merge (aliases: other "you" ids count as you).

**D-151 (history) — the sync round-trip proofs, both shells.** Founder: "go ahead with the
rest of the build" (answered: ~5–7 sessions / 20–30 units left). CAUSES FOUND: (1) iOS: Swift JSONEncoder key
order is not stable (same value, different order on alternate encodes) → kv ledger rebuilt from records = same
data, different bytes. FIX: `LedgerStore.kvEncoder()` (.sortedKeys) for every kv ledger write (expenses,
settlements, groups; GroupStore.write); record blobs untouched. New relay-free `ProjectionBytesTests` (3; all
RED on the old code, proven) + Android twin `ProjectionBytesTest` (2). (2) Both proofs asserted `ledger/groups`,
which record sync does not carry (D-121 (3), §4) → dropped from the asserted set with a comment. (3) Android's
proof never cleared the RECORDS (iOS's did) → nothing new pulled, no rebuild, ledger [] → now clearRecords().
(4) iOS proof used a fixed identifier → worked once per relay → per-run identifier + device id (Android's shape).
RESULT with a local dev relay: iOS 179 executed, 0 skipped, 0 failures (first all-relay run); Android
SyncProofLiveTest passes; iOS round trip repeats on one relay. pbxproj A10117/B10132. SABOTAGE 6/6 OK (P1–P4 iOS, A1–A2 JVM; relay up).
FULL GATE STARTED WITH THE RELAY UP (relay tests run, not skip); then record, commit.

**D-150 SHIPPED (in-app account deletion; committed with this WORKLOG) — 2026-09-24.** Gate 20/20 in 7m38s;
cargo 352; iOS 176/8/0; JVM 189×2; sabotage 13/13. Seen end to end on iOS 27 + API 34 against a local dev relay.
D-149 residue (a)/(b) closed in place. Residue: the iOS sync round-trip E2E fails whenever a relay runs (HEAD
too) — offered to the founder as a separate task; no plain sign-out in Settings on either shell.
**NEXT (pick with the founder):** fix the sync round-trip E2E (a real sync question: does a restored
`ledger/expenses` differ from what was pushed?); then the earlier list: web/fonts Plex woff2, Android kit Save
PDF/Print, app lock, default currency, import, settle hand-off, multi-person group sync, privacy policy, store items.
Developer Tools exemption still ON (internal testing) — remind before any signed/store build.

**D-150 (history) — in-app account deletion, both shells.** Founder DEPLOYED D-149: live
`/version` = `03e6abf…`, `/v1/dev/session` = 401 (checked 2026-09-24). Design: Settings → Account
controls (only with a session) → "Delete account" card → DeleteAccountScreen (tap 2 = Delete; no extra
"are you sure": the 7-day grace is the undo). Screen: what goes (everything the server keeps), what stays
(this device's ledger), the grace, Export first (button BEFORE Delete), Delete tinted owed. Client:
deleteAccount returns .scheduled(deleteAfterMs) on 202 / .deletedNow on 204; AccountDeletion.request
refreshes once on 401 then retries; success → LedgerSync.signOut (ledger kept). `deletion_cancelled`:
SessionCreatedBody gains it; RelaySession gains a TRANSIENT flag (iOS: excluded from CodingKeys so stored
sessions still decode; Android: @Transient) → Settings shows "Your account deletion was cancelled."
**D-150 progress (2026-09-24 14:40):** BUILT both shells + `--deletion-proof` launch arg on each (dev mint →
Settings; live relay 401s the mint). iOS 176/8/0 (166+10 AccountDeletionTests); JVM 189×2 (177+12
AccountDeletionTest); pbxproj A10115/A10116, B10130/B10131. SEEN end to end against a local dev relay
(`.drafts/d150/relay*`): iOS 27 + API 34, light + dark: card → screen → Delete → 202 "deleted on Oct 1, 2026"
(Android "1 Oct 2026") → Settings "No account"; relaunch → relay `r_deletions` 0 → "Your account deletion was
cancelled."; Export opens from the flow and Back returns to it. Shots in `.drafts/d150/shots/`.
FINDING (pre-existing, NOT D-150): with a relay running, iOS `SyncE2ETests.testRoundTripThroughTheRelayIsByteIdentical`
FAILS ("kv bytes diverged at ledger/expenses"); reproduced on HEAD 03e6abf with my diff stashed (restored
byte-identical). Normally hidden as one of the 8 relay skips. Spun off as a separate task; record in §4.
SABOTAGE 13/13 OK (`.drafts/d150/results_d150.json`; I1–I6 iOS, A1–A7 JVM both flavours); tree verified
identical to the pre-sabotage backup. Local relay STOPPED. Record drafts `.drafts/d150/final/` (+ close_old/new.txt
closing D-149 residue (a) deployed and (b) apps read 202 IN PLACE). FULL GATE STARTED (log `.drafts/d150/gate.log`).
NEXT: record (record_unit.py --unit D-150 --prev D-149 --dir .drafts/d150/final --sabotage results_d150.json),
buildlog_edit the closures, copy shots to ios/artifacts + android/artifacts (d150-*.png), commit, push.

**D-146 SHIPPED (About + Licences; recorded, committed with this WORKLOG) — 2026-09-22.** Full gate
**20/20** in 8m19s (new leg: licence inventory); iOS 165/8/0; JVM 171×2; sabotage 14/14. NOTICE gained
SQLCipher, OpenSSL, fonts, Feather MIT. `scripts/licences.py` + `native/licences/licences.json`.
record_unit.py now ends the evidence at the "licence inventory" line and renders script sabotage rows.

**FOUNDER ANSWERS (2026-09-22, AskUserQuestion):**
1. IBM Plex reserved name "Plex": **ship IBM's unmodified files** (download IBM's official Plex Mono
   Regular + Medium from IBM's GitHub release, font data only, never executed; replace
   `native/fonts/IBMPlexMono-{Regular,Medium}.ttf` + `android/app/src/main/res/font/ibmplexmono_*.ttf`;
   regenerate licences.json; web/fonts woff2 stays residue). State filename, source and size when downloading.
2. **Yes: give the skipti-shots emulator a test PIN + emulated fingerprint** for D-147; remove both after
   (`adb shell locksettings set-pin 1111` … `locksettings clear --old 1111`; enrol via Settings + `adb emu finger touch 1`).
3. Account deletion: **build the relay's 7-day grace first** (pending state, cancel on sign-in, purge after
   7 days; tests; deploy commands for the founder), then the in-app screen.

**D-147 SHIPPED (Android Recovery Kit re-view; committed with this WORKLOG) — 2026-09-22.** Gate 20/20 in
7m38s; JVM 176×2; iOS 165/8/0; sabotage 7/7 (K1–K6 JVM both flavours + D1 by hand on the emulator).
Emulator test PIN + fingerprint are REMOVED right after this commit (`locksettings clear --old 1111`).

**D-148 SHIPPED (IBM's unmodified Plex Mono; committed with this WORKLOG) — 2026-09-22.** Gate 20/20 in
7m41s; iOS 166/8/0; JVM 177×2; sabotage 4/4. PostScript names now `IBMPlexMono` / `IBMPlexMono-Medm`
via TypeRole.plexRegular/plexMedium; hashes pinned in licences.py + AboutTest(s). web/fonts Plex subsets =
D-148 residue (a). D-146 residue (a) closed in place.

**D-149 SHIPPED (relay 7-day deletion grace; committed with this WORKLOG) — 2026-09-24.** Gate 20/20 in
7m42s; cargo 352; iOS 166/8/0; JVM carried forward (no Android input changed); sabotage 8/8 (G1–G8).
Schema v4 `r_deletions`; delete = 202 {delete_after_ms}, sessions end at once; any sign-in cancels
(`deletion_cancelled: true`); hourly sweep in main.rs; passkey path now uses mint_verified_session;
purge commits the vault before memory. THREAT_MODEL updated. **NOT DEPLOYED**: live relay is `9952f95`;
the founder deploys via DEPLOY-ORACLE §7.1 (one-way migration v3→v4; back up first). Tell him.

**NEXT: D-150 in-app account deletion (both shells).** SPEC §4.4: one screen, two taps from Settings,
export offered first in the same flow, grace "clearly stated", no repeated "are you sure". Only for a
signed-in account (GUARANTEES #5: absent without one). Clients: iOS `IdentityService.deleteAccount`
(ios/Skipti/Relay/IdentityService.swift:291) must accept 202 {delete_after_ms} (and 204 = old relay,
deleted now); Android has NO delete call yet (add to IdentityService.kt). After success: drop the local
session (LedgerSync.signOut) but keep the local ledger (never auto-delete user data). On any sign-in,
read `deletion_cancelled` and say "Your account deletion was cancelled." (SessionCreatedBody on both).
Tests with the fake transport (RelayClientTests 199/209/229/327 expect 204 today). Relay URL is fixed
(RelayConfig; `--relay-local` launch arg for a local relay: `cargo run -p skipti-server`, then
`adb reverse tcp:8080 tcp:8080` on Android) — use a local relay to see the flow end to end.

**FOUNDER ANSWERS (2026-09-22, AskUserQuestion):**
1. IBM Plex reserved name "Plex": **ship IBM's unmodified files** (download IBM's official Plex Mono
   Regular + Medium from IBM's GitHub release, font data only, never executed; replace
   `native/fonts/IBMPlexMono-{Regular,Medium}.ttf` + `android/app/src/main/res/font/ibmplexmono_*.ttf`;
   regenerate licences.json; web/fonts woff2 stays residue). State filename, source and size when downloading.
2. **Yes: give the skipti-shots emulator a test PIN + emulated fingerprint** for D-147; remove both after
   (`adb shell locksettings set-pin 1111` … `locksettings clear --old 1111`; enrol via Settings + `adb emu finger touch 1`).
3. Account deletion: **build the relay's 7-day grace first** (pending state, cancel on sign-in, purge after
   7 days; tests; deploy commands for the founder), then the in-app screen.

**D-147 SHIPPED (Android Recovery Kit re-view; committed with this WORKLOG) — 2026-09-22.** Gate 20/20 in
7m38s; JVM 176×2; iOS 165/8/0; sabotage 7/7 (K1–K6 JVM both flavours + D1 by hand on the emulator).
Emulator test PIN + fingerprint are REMOVED right after this commit (`locksettings clear --old 1111`).

**D-148 SHIPPED (IBM's unmodified Plex Mono; committed with this WORKLOG) — 2026-09-22.** Gate 20/20 in
7m41s; iOS 166/8/0; JVM 177×2; sabotage 4/4. PostScript names now `IBMPlexMono` / `IBMPlexMono-Medm`
via TypeRole.plexRegular/plexMedium; hashes pinned in licences.py + AboutTest(s). web/fonts Plex subsets =
D-148 residue (a). D-146 residue (a) closed in place.

**D-149 IN FLIGHT (uncommitted) — relay grace BUILT, all server tests + clippy green:** schema v4 `r_deletions`;
`account_delete` = schedule (ends sessions, 202 {delete_after_ms}); `cancel_pending_deletion` in BOTH mint
paths (passkey_auth_finish now calls mint_verified_session — it was a copy); `purge_account` writes the vault
FIRST then memory; `purge_due_deletions(state, now)` + hourly sweep in main.rs; `deletion_cancelled` only
when true. Tests: main deletion test reworked (grace, restart during grace), 2 new cancel tests (OTP, passkey),
Apple test purges after grace, persist v3→v4 test. Sabotage 8/8 OK (`.drafts/d149/results_d149.json`),
THREAT_MODEL updated, drafts `.drafts/d149/final/`. FULL GATE started. iOS/Android client 202 handling = D-150.

**NEXT: D-149 relay 7-day deletion grace** (founder: "Build the grace first"). Read so far: SPEC §4.4 (one
screen, two taps, export first, 7-day grace "with a cancel link, clearly stated, then irreversible", last
member destroys the group, verified by querying the store); relay `account_delete` in server/src/lib.rs:640
purges at once (registry, sessions, store, envelopes, escrow, passkeys, OTP; one vault transaction
`delete_account` in persist.rs:567); tests server/tests/delete_account_http.rs (555 lines, store queried
over SQL + restart). iOS client `IdentityService.deleteAccount` expects 204 (RelayClientTests 199/209/229/327).
PLAN: request = revoke the subject's session families + persist a pending row (new table r_deletions:
subject_id, requested_wall_ms; CREATE IF NOT EXISTS, additive) + respond 202 {"delete_after_ms": wall ms};
any successful sign-in for that subject during grace CANCELS (auth response gains `deletion_cancelled: true`);
a purge task (boot + hourly) runs the existing purge for rows older than 7 days; refactor the purge body
into one fn; clock injectable for tests; update THREAT_MODEL ("not yet built" line), DEPLOY-ORACLE commands
for the founder (he deploys). Clients: accept 202 (scheduled) and 204 (old relay: immediate). Then D-150 UI.

**FOUNDER ANSWERS (2026-09-22, AskUserQuestion):**
1. IBM Plex reserved name "Plex": **ship IBM's unmodified files** (download IBM's official Plex Mono
   Regular + Medium from IBM's GitHub release, font data only, never executed; replace
   `native/fonts/IBMPlexMono-{Regular,Medium}.ttf` + `android/app/src/main/res/font/ibmplexmono_*.ttf`;
   regenerate licences.json; web/fonts woff2 stays residue). State filename, source and size when downloading.
2. **Yes: give the skipti-shots emulator a test PIN + emulated fingerprint** for D-147; remove both after
   (`adb shell locksettings set-pin 1111` … `locksettings clear --old 1111`; enrol via Settings + `adb emu finger touch 1`).
3. Account deletion: **build the relay's 7-day grace first** (pending state, cancel on sign-in, purge after
   7 days; tests; deploy commands for the founder), then the in-app screen.

**D-147 SHIPPED (Android Recovery Kit re-view; committed with this WORKLOG) — 2026-09-22.** Gate 20/20 in
7m38s; JVM 176×2; iOS 165/8/0; sabotage 7/7 (K1–K6 JVM both flavours + D1 by hand on the emulator).
Emulator test PIN + fingerprint are REMOVED right after this commit (`locksettings clear --old 1111`).

**D-148 BUILT, sabotage 4/4 OK, FULL GATE STARTED (Plex originals):** fonts copied (both shells byte-identical,
hashes pinned in licences.py + a test per shell), TypeRole.plexRegular/plexMedium = IBM names, iOS 166/8/0,
JVM AboutTest 7/7; drafts `.drafts/d148/final/`. Earlier notes: downloaded `.drafts/d148/ibm-plex-mono.zip` (IBM/plex release
`@ibm/plex-mono@2.5.0`, 2026-06-11, 6,940,652 bytes, sha256 6d23f012…481c), extracted only
`fonts/complete/ttf/IBMPlexMono-{Regular,Medium}.ttf` (173,052 / 174,008 B) + LICENSE.txt into
`.drafts/d148/x/`. FOUND: IBM 2.5.0's PostScript names are `IBMPlexMono` and `IBMPlexMono-Medm` (the
google/fonts 2.3 subset said `IBMPlexMono-Regular` / `-Medium`); version 2.005; 1,049 code points incl.
₹ ₽ € − · Á é. iOS resolves by PostScript name in THREE places (TypeSystem.swift plexRegular/plexMedium,
RecoveryKitPDF.swift:146/149, BalanceLineTests.swift:366/370 force-unwraps) → centralise the names and
add a test that UIFont(name:) resolves both. Android loads by res file (no names). Update native/fonts
README, web/fonts README (woff2 subset still carries the reserved name = residue), NOTICE, licences.json.

**NEXT UNITS (D-147 done):** ~~D-147 Android Recovery Kit re-view — drafts `.drafts/d147/{KitReveal.kt,KitRevealTest.kt}`~~
(platform BiometricPrompt API 28+, BIOMETRIC_STRONG on 30+, biometrics only like iOS, honest message on
API 26–27, FLAG_SECURE via `SecureWindow()`; add a Recovery Kit card to Android Settings → Your data, route
"kit" with HomeRoutes parent "account"; consider SecureWindow on onboarding's kit display too).
D-148 Plex originals. D-149 relay grace (read SPEC §4.4, server delete route D-62). D-150 in-app deletion
(both shells, two taps from Settings, export offered first).

**Shipped today (all pushed with [skip ci]; no Actions runs):** D-141 `206381a` (expense revisions
in core), D-142 `08ab221` (iOS edit/delete/Undo/long-press/§6.1 prompt), D-143 `ea88e31` (Android
twin + system Back steps back through routes), D-144 `404de28` (Settings: System/Light/Dark/Night
per ADR-0036; Export + iOS Recovery Kit reachable — GUARANTEES #4 had been false in the product;
D-119 corrected in place; Android button-label contrast fixed; AppDate), **D-145 `3971b39` (on-device profile:
name + emoji-or-photo avatar).** Nothing in flight; the tree is clean.

**Gates are fast now:** the founder added the INNER claude.app to Developer Tools (2026-09-21);
D-144 8m15s, D-145 7m28s (were 20–40 min). If they slow again after a Claude update, the versioned
path (`~/Library/Application Support/Claude/claude-code/<version>/claude.app`) changed: re-check
process ancestry and ask him to add the new one.

**Founder decisions today:** Night = pure black (ADR-0036); avatar = emoji OR photo; he asked "how
long to a full experience" — answered ~25–35 units / 1.5–3 weeks of sessions + store lead times
(Settings rest ~5; multi-person groups ~6–8 = biggest gap: groups do not sync between people;
settle hand-off UPI/Venmo/PayPal, Splitwise import with member mapping, recently deleted,
settlement edit ~5–7; accessibility, Android insets API 35+, privacy policy page, PrivacyInfo /
Data Safety, store listing, release signing + TestFlight/Play internal ~6–10, some his).

**NEXT UNITS, in order (the founder: "things an actual app usually has"):**
1. **D-146 About** (both shells, a Settings section + page): app version + build (iOS
   CFBundleShortVersionString/CFBundleVersion, Android versionName/versionCode) and core version
   (`skiptiCoreVersion()`); "Made by Vardr Labs"; the seven guarantees (short forms of
   GUARANTEES.md 1–7); a privacy summary ("Encrypted on your device…", no telemetry); security
   contact security@vardrlabs.com (mailto); **Open-source licences** page listing Apache-2.0
   (SKIPTI), the BIP-39 wordlist (MIT), Lucide (ISC), UniFFI + webauthn-rs (MPL-2.0), webpki-roots
   (CDLA-Permissive-2.0), and the two bundled fonts — **Space Grotesk + IBM Plex Mono are SIL OFL
   1.1 and are MISSING from NOTICE**: add them; take their copyright + licence text from the TTFs'
   own name tables (name IDs 0, 13, 14; `web/fonts/README.md` says the licence rides in the name
   table; parse with a small stdlib Python reader, no fontTools). The repo is PRIVATE
   (github.com/icrkdev/Skipti): do NOT link source code. No privacy-policy URL exists yet.
2. **D-147 Android Recovery Kit re-view** (iOS has KitRevealScreen behind Face ID): platform
   `android.hardware.biometrics.BiometricPrompt` (API 28+; minSdk 26 → fall back to
   KeyguardManager device credential) — NO new dependency; link it from Settings → Your data.
3. **D-148 in-app account deletion** (SPEC §4.4, App Store requirement): Settings, two taps, export
   offered first, the relay's `POST /v1/account/delete` exists (D-62); SPEC's 7-day grace is NOT in
   the relay (would need server work the founder deploys) — ask him before building grace.
4. Then: app lock (biometric on open, SPEC Phase 7), default currency, iOS import file picker +
   member mapping (both shells' import is not product-grade), settle hand-off, multi-person groups.

**Working notes that matter:**
- Recorder: `.drafts/common/record_unit.py --unit D-NNN --prev D-MMM --dir .drafts/dNNN/final
  --gate-log .drafts/dNNN/gate.log --gate-start .drafts/dNNN/gate.start --sabotage <json>`;
  templates row/section3/residue/changelog (+commit.md); fill custom placeholders by hand.
- Sabotage runner template: copy `.drafts/d145/sabotage_d145.py` (machinery from D-141; rows
  CORE/IOS/ANDROID; `--check` validates anchors; `python3 runner.py I4` reruns one row).
- Android emulator taps: `.drafts/d144/tap.sh tap|long|text|key` (refuses unless SKIPTI or its own
  Pop-Up Window has focus). Pixel sampling: `.drafts/d144/px.py <png> x,y ...`.
- iOS appearance: set in-app (simctl `defaults write` does not reach the app container). Both
  gate devices were left on System.
- Open items for the founder: TestFlight runbook (offered); Developer Tools must be OFF before any
  signed/store build (§13); the Android status bar on API 35+ (D-138 (e)).

**D-140 RECORDED (full gate 19/19 in 27m59s, 2026-09-19T06:21:50Z; JVM 276 fresh) — account glyph on every tab.** iOS: `HomeShell.accountItem` (a
`@ToolbarContentBuilder`, pinned on 27) on all four tab roots. Android: `AccountBar` in the shell above
every tab; Balance's own glyph and its `onOpenAccount` parameter removed. Seen: iOS 27 Add/Expenses/
Settle (+ account opens from Expenses); Android skipti-shots all four tabs (+ opens from Settle).
Incident on the way: system Back on Android's Account route exits the app (D-136 residue (b)), and
my next taps hit the launcher and opened Messaging's empty compose screen; nothing typed or sent;
force-stopped. Scripts now check SKIPTI has focus before each tap. No new guard → no sabotage.
**Found 01:41: Android Studio's sync (the founder opened Studio to start the Pixel AVD) changed
`android/gradle.properties` (+`org.gradle.tooling.parallel=true`, IDE-only) and created
`android/gradle/gradle-daemon-jvm.properties` (daemon JVM = JDK 25, foojay.io download URLs).** The
first D-140 gate was killed in clippy before any Gradle leg. Nothing downloaded (`~/.gradle/jdks`
absent; Studio's JBR is 25.0.2). Founder delegated the call: KEEP the parallel line (annotated);
MOVE the daemon-JVM file to `.drafts/d140/studio-sync/` and gitignore it; ADD
`org.gradle.java.installations.auto-download=false` so Gradle can never fetch a JDK (§13). Told him
not to download anything Studio offers. Gate restarted.
Next: full gate → record → commit; then D-141 (core edit/tombstone records).

**FOUNDER, 2026-09-19 (after D-139):**
- Developer Tools is ON for Claude, Terminal and Xcode, and he restarted Claude. **FINDING:** my
  shell's ancestry is zsh → `~/Library/Application Support/Claude/claude-code/2.1.275/claude.app`
  (com.anthropic.claude-code, team Q6L2SF6YDW) → `/Applications/Claude.app/Contents/Helpers/disclaimer`
  → Claude.app. The `disclaimer` helper makes the inner claude.app responsible for its children, so
  the exemption granted to /Applications/Claude.app never reaches the gate. Told him: add the INNER
  app (⌘⇧G path) if he wants it; its folder is versioned (2.1.271, 2.1.275 exist), so an update may
  need re-adding. His call (security setting).
- TestFlight: asked me to put it there. Told him: not mine (App Store Connect + his signing identity,
  CLAUDE.md §2/§11); I can prepare the Release signing settings and a runbook; **§13: Developer Tools
  must be OFF before any signed/store build** (reminder given). Signing in on the simulator/emulator
  is safe with caveats (he types credentials; sign out after; Google test users only; a real account
  on the live relay; use the iOS 26.5 sim EB725812 so my iOS 27 gates don't collide; Pixel AVD for
  Google).
- **Group switcher: leave it as it is** (decision).
- **Account icon on EVERY tab** (not only Balance), both platforms → D-140.
- **Edit/delete expenses** (essential): decided **tap → detail page with Edit and Delete, plus a
  long-press quick menu**; **edit AND delete**, delete confirmed + Undo. SPEC already fixes semantics
  (§5.7, §6.1): edits = new versions, prior kept until compaction; deletes = tombstones; field-level
  LWW by HLC; AMOUNT conflicts surfaced ("Keep ₹1,200 or ₹1,250?"); "added by / last edited by" with
  time. Today: one record per expense (whole ExpenseDTO JSON), identity = HLC, no stable expense id;
  projection is in each shell (Swift `Sync.rebuildProjection`, Kotlin twin). Plan: D-141 core (edit +
  tombstone record kinds keyed by the creation record's identity; money fields one atomic register;
  conflict = two edits on the same base version with different money; `ffi_project_ledger` shared by
  both shells), D-142 iOS UI, D-143 Android UI.

**D-139 SHIPPED as `48856c7`** (pushed; no Actions run; the hook skipped the heavy legs on the
full gate's proof although gate.sh and the hook themselves changed). The three planned units are
DONE: D-137 `de84405`, D-138 `a9048f4`, D-139 `48856c7`. **NEXT (nothing queued):** founder's answers
below; candidates: Android edge-to-edge (spawned task), the founder's end-to-end sign-in test,
the group-switcher-in-toolbar question, then the Roadmap to publish section further down.
**Open questions for the founder (2026-09-19):** (1) re-grant Developer Tools / quit+reopen Claude
(scan still in every gate); (2) try Apple + Google sign-in with his own accounts (sim + Pixel AVD);
(3) group switcher as an iOS 27 toolbar item, yes/no; (4) edge-to-edge task chip.

**D-139 (record kept) — iOS 27 adoption.** Was uncommitted:
- `formActionBar { }` (DesignTokens.swift): `safeAreaBar` on iOS 26+, `safeAreaInset` on the page
  colour before; an opaque page-colour backing so a dimmed button never shows rows through it.
  Used for Add expense, Save name, Create group, Send code/Verify, Import N expenses (Import's
  footer copy moved into its status row). "Add line" separator hidden.
- HomeShell: the account glyph is `.topBarPinnedTrailing` on iOS 27 (one `#available` gate).
- RecordSyncTests: the 13 `#NoUseUnstructuredThrowingTask` Tasks now `do/catch` → `XCTFail("restoreFromRelay
  threw: …")`, and one needless `try` removed; only the 2 old Sendable warnings remain.
- New gate leg `scripts/check-swift-list-rows.py` (**19 legs**; cheap 7, fast 10): flags a styled button
  made a clear, zero-inset row; walks the modifier chain (v1's 8-line window missed Sign in).
  Flags all 4 historic sites on HEAD's tree, 0 now. CLAUDE.md §3/§4/§6/§9.1/§10.1 counts + guidance
  updated; record_unit.py PASSED → 19.
- Seen: iOS 27 Add expense (bar rides above the keyboard), Sign in light/dark, New group, Itemised;
  iOS 26.5 (founder's ledger, vault backed up to `.drafts/ios27/founder-vault-backup-20260919T045805Z/`,
  sha256 1fefb941…) Add expense. iOS 135/8/0.
- **Sabotage 3/3** (`.drafts/ios27/results_d139.json`): L1/L2 scan names the file; R1 restoreFromRelay
  throws → exactly the 8 callers red, log says "restoreFromRelay threw:", NO timeouts.
- NOT done (ask/residue): group switcher as an iOS 27 toolbar item (a product choice: 27-only layout
  change), item-binding dialogs (no visible change), toolbar minimise.
- **FULL GATE 19/19 PASSED in 26m30s** (2026-09-19T05:27:07Z; clippy 252 s and cargo test 850 s:
  the scan again, after two commits); iOS 135/8/0 fresh; JVM 276 carried forward. **D-139 RECORDED**
  (D-01..D-139). Committing.

**D-138 SHIPPED as `a9048f4`** (pushed; no Actions run; the hook ran the cheap legs only, since
nothing heavy was staged). **NEXT: D-139 iOS 27 adoption** (`.drafts/ios27/NOTES.md`). SKIPTI play
debug stays installed on the Pixel_10_Pro_XL AVD (no data) for the founder's Google test.
**FOUNDER TO-DO (tell him):** (1) Developer Tools exemption is not reaching Claude's gates
(re-grant / quit+reopen Claude when no gate runs); (2) sign the iOS 27 simulator into an Apple
Account and try both buttons, and add a Google account on the Pixel AVD and try Google there;
(3) the spawned task for Android edge-to-edge; (4) Google consent screen is in Testing mode and
Play App Signing's SHA-1 client comes with the Play listing.

**D-138 (record kept) — Google sign-in go-live.** Was uncommitted:
- iOS: client ID wired; `brandMarkBundled = true`; `GoogleSignInButton.imageset` (Google's Square
  art @1x/2x/3x, light and dark); `GoogleSignInButton` view and `ProviderArtButtonStyle` in
  GoogleSignIn.swift; SignInScreen stacks Apple and Google at one size (188×44) with Apple's
  corners at 4 to match; copy "Apple or Google only confirm it is you. They never see your
  ledger."; `signInWithGoogle()`.
- Android: WEB_CLIENT_ID wired; `BRAND_MARK_BUNDLED` now per flavour (GoogleCredential companion);
  `src/play/res/drawable{,-night}-{m,xh,xxh,xxxh}dpi/google_sign_in.png`; GoogleSignInButton.kt in
  play (Image) and direct (draws nothing); SignInScreen shows it above ACCOUNT with "Google only
  confirms it is you. It never sees your ledger."; `useGoogle()`.
- NOTICE: Google's button art (trademark, not under our licence).
- Tests: iOS +2 (client-ID/redirect golden; art light≠dark at 188×44) → **135 executed / 8 skipped
  / 0 failures**; JVM +1 shared (web-ID golden) +1 per flavour twin → **276 = 138 × 2, 0 failed**.
  Dex: direct 0 googleid / 0 playservices / 0 art refs; play 6 / 3 / 2.
- **Seen:** iOS 27 sim light + dark; tapping Google fetched a nonce from the LIVE relay and Google
  answered "Sign in to continue to SKIPTI" (no redirect_uri_mismatch) → client ID, redirect, PKCE
  and scope accepted; closing the sheet = no error. Apple: the relay nonce arrived, then iOS said
  "Sign in to your Apple Account" (the sim has none) → closing shows "Sign in with Apple did not
  finish." Android: direct (skipti-shots) has no button; play on Pixel_10_Pro_XL (API 37, Play
  services, no Google account) → Credential Manager routed TYPE_GOOGLE_ID_TOKEN_CREDENTIAL to
  GoogleIdService and Google's add-account flow opened; Back twice → back in SKIPTI, no error.
  skipti-shots had lost DNS after hours up (error text leaked raw "Unable to resolve host…", the
  existing RelayError.Network copy). Found and spawned as a task: **Android edge-to-edge** (API 35+
  draws under the status bar; targetSdk 37, no insets anywhere).
- **Founder's part for a full end-to-end:** sign the iOS simulator into an Apple Account (Settings)
  and complete Google with his own Google account on the sim / an AVD. I never type credentials.
- **Sabotage 7/7 OK on the first run** (`.drafts/d138/results_d138.json`): I1 iOS web-ID → the golden;
  I2 mark false → availability; I3 dark→light art → the art test; A1 Android iOS-ID → web golden in
  both variants; A2 play mark false → play only; A3 direct mark true → direct only; A4 art copied
  into direct/res → direct only. All restored (direct/res removed). record_unit.py now renders an
  EXPECTED per-flavour split. **FULL GATE 18/18 PASSED in 24m35s** (2026-09-19T04:45:36Z; clippy
  246 s and cargo test 783 s again, because server/build.rs watches .git/logs/HEAD, so each commit
  relinks every server test binary and macOS scans them anew). iOS 135/8/0 fresh; JVM direct fresh
  in the gate, play reused A4's identical-input XML and was rerun fresh after the gate (138/0);
  recorded with record_unit.py's new --jvm-note. **D-138 RECORDED** (D-01..D-138). Committing.

**D-137 SHIPPED as `de84405`** (pushed; no Actions run). Its commit went through the NEW hook:
"pre-commit: heavy legs skipped — the full gate at 2026-09-19T01:40:08Z (HEAD 2ca8f08) passed on
exactly these 68 staged heavy inputs and this toolchain.", then the cheap gate 6/6 in 4 s. **NEXT:
D-138 Google sign-in go-live** (`.drafts/d138/NOTES.md`), then D-139 iOS 27 adoption. Open for
the founder: the Developer Tools exemption is not reaching Claude's processes (finding below).

**D-137 (record kept) — gate speed.** Design as built is in
`.drafts/d137/NOTES.md` ("Refined design"). Uncommitted: new `scripts/gate_inputs.py` (the
heavy-leg proof: denylist of paths cargo never reads, toolchain in the digest, marker at
`.git/skipti-heavy-legs-pass.json`), new `scripts/test_gate_inputs.py` (21 tests, ~4 s; a new
gate leg in every mode, so the full gate becomes **18 legs**, fast 9, cheap 6), `scripts/gate.sh`
(`--cheap`, per-leg `(Ns)`, Timing table after the evidence, marker finish), `.githooks/pre-commit`
(cheap legs on EVERY commit; heavy legs when a heavy input is staged unless the proof matches).
Self-test green, and green with GIT_INDEX_FILE/GIT_DIR pointed at the real repo (real index
unchanged). **Sabotage 10/10 OK on the first run** (`.drafts/d137/sabotage_gate.py`,
`results_gate.json`). **R1 real fast gate: 9/9 PASSED in 20m37s** (clippy 456 s, cargo test
777 s for 327 tests, self-test 4 s; `.drafts/d137/r1-fast.log`), marker written over 68 heavy
inputs; after staging the D-137 scripts, `check-index` matched the index. **R2** (cargo stub,
fmt fails): GATE FAILED → "marker: cleared", file gone. **R3** (stub appends to core/src/lib.rs
during clippy): gate passed → "not written (the heavy inputs changed while the gate ran)";
lib.rs restored, cmp identical. Docs APPLIED (`.drafts/d137/apply_docs.py`: CLAUDE.md §2/§3/§4/
§6/§7/§8/§9.1, DEPLOY-ORACLE §7 commit file + §7.1 "Updating a running relay", WORKLOG gap
closed) and the 5 BUILD-LOG "(Corrected D-137: …)" edits; gate.sh header now states the
scanning finding. record_unit.py PASSED → 18. **FULL GATE 18/18 PASSED in 8m54s**
(2026-09-19T01:40:08Z; clippy 0 s and cargo test 37 s warm, vs 456 s / 777 s in R1; iOS 133 / 8
skipped / 0 failures fresh; JVM 272 carried forward), marker written (full, 68 inputs, digest
9f11ca917174). **D-137 RECORDED** in BUILD-LOG (D-01..D-137 contiguous); record_unit.py now cuts
the evidence at the Timing table and fills {{TIMING}}. One CLAUDE.md line (§4 full-gate time)
was corrected after the gate, disclosed in §3. Committing now; then D-138
(`.drafts/d138/NOTES.md`: plan, iOS imageset and Android play drawables already staged there).
**Sign in with Apple is ENABLED on the App ID (founder, 2026-09-18).**
**FINDING (2026-09-18 ~21:10Z): the Developer Tools exemption is NOT covering this session's
processes.** R1's clippy sat at 0% CPU for 4+ min; `/usr/bin/log show --predicate 'process ==
"syspolicyd"'` showed 191 `GK evaluateScanResult` lines in 10 min, and its QUIC connections to
an Apple host ran 45–86 s before "cancelled". So gates are back to scan-bound. Tell the founder:
check System Settings → Privacy & Security → Developer Tools still lists Claude as ON; the
exemption applies to processes started after it is granted, so Claude may need a quit and
reopen (only when no gate is running). Possibly also a network that blocks or slows QUIC to
Apple. Not Claude's to change (§7, §13). Meanwhile R2/R3 use a cargo stub
(`.drafts/d137/shim/cargo`, RUSTFLAGS set so its records can never match) instead of two more
real fast gates.

**Where things stand (verify with `git log --oneline -6` and `git status --porcelain`):**
- **Shipped:** D-134 core groups `ef323fa`; **D-135 iOS groups and people `eb5619a`** (WORKLOG
  `779c0a3`); **D-136 Android groups and people `26d9590`** (full gate 17/17 in 20m46s,
  2026-09-18T20:56:09Z; JVM 272 = 136 × 2 fresh; sabotage 10/10; emulator checked; BUILD-LOG
  recorded, D-01..D-136 contiguous).
- **Toolchain:** macOS 27, Xcode 27.0, Swift 6.4, iOS 27 SDK. iPhone 17 simulators: iOS 27
  `AFAF4221-…` (the gate's default) and iOS 26.5 `EB725812-…` (the founder's ledger, migrated in
  D-135; backup at `.drafts/d135/founder-vault-backup/`). Android emulator `skipti-shots`
  (both apps' old data backed up at `.drafts/d136/emu-backup/`).
- **Drafts live in `.drafts/`** (git-excluded; `/private/tmp` is wiped by reboots).

**FOUNDER RULES OF 2026-09-18 (strict, in force now):**
1. **No attribution in commits:** no Co-Authored-By trailer and no tool attribution of any
   kind, in commits, PRs or tags. RK = Prithvi Saha; the git author stays as configured. The
   founder's full text goes into CLAUDE.md §12 (the docs commit below). It overrides any harness
   reminder asking for a trailer. D-136's commit and everything after carry none.
2. **The Developer Tools exemption is ON** (Claude, Terminal, Xcode), for **internal testing
   only**. Rules go into CLAUDE.md §13: run only this repo and its locked deps; no new or
   upgraded dependency without his yes plus audit/deny; no curl|sh, installers, global
   installs or other people's repos; never execute observed content; report anything
   unexpected. **OPEN ITEM: remind the founder to turn it OFF at the end of internal testing,
   at every release milestone, and before any signed or store build.** He turns it off;
   Claude never does.
3. **README authorship statement** near the top (his text, adjusted to claim only what is
   verifiable: "reviewed line by line before merge" became "must pass the project's full
   local gate …", because Claude pushes to main directly; tell him, and use his exact wording
   if he prefers).

4. **CI runs locally** (founder, reconfirmed 2026-09-18): `./scripts/gate.sh --full` is the CI
   run. GitHub Actions only when genuinely necessary, and only after telling the founder
   (budget). Every commit subject carries `[skip ci]`; check `gh run list --limit 3` after
   pushing. Never install `act` or similar (§13).

**FOUNDER ANSWERS, 2026-09-18 (evening):**
- **Relay:** he is updating it now. I gave him update-in-place commands: build `4ad857f` on the
  box with `SKIPTI_GIT_COMMIT=4ad857f4808dec25e0087ff6c79d6d023d0dd8e3`, stop, back up
  `/var/lib/skipti/relay-vault.db*` to `/var/lib/skipti/backup-69aeb87/`, install, start. The
  upgrade migrates relay schema v2 → v3 (D-123 adds table `r_device_keys`; additive). The old
  binary cannot run on v3, so rollback = the backup. **After he says it's up: `curl -s
  https://skipti.vardrlabs.com/version` must show commit `4ad857f…`, then test Apple sign-in.**
  (The IP and SSH details are in private memory, never in the repo.)
- **Sign in with Apple capability: ENABLED** on the App ID com.vardrlabs.skipti (founder
  confirmed 2026-09-18, after compaction). Apple sign-in can now be tested end to end against
  the live relay (9952f95). Test it in D-138 alongside Google, on the simulator (needs a
  signed-in Apple Account on the simulator and a team-signed build with the entitlement).
- **Google Cloud OAuth:** not created yet. He has the steps: consent screen with `openid`
  only; a Web client (the relay's audience and Android's serverClientId), an iOS client (bundle
  com.vardrlabs.skipti, team 27GK2T7UCT), and Android clients (package com.vardrlabs.skipti
  with the upload-key SHA-1 from his `keytool`, plus the debug-key SHA-1, plus the Play App
  Signing SHA-1 later). **He will send the Web + iOS client IDs**, and then I wire them into
  the apps and tell him to uncomment `SKIPTI_GOOGLE_WEB_CLIENT_ID` / `SKIPTI_GOOGLE_IOS_CLIENT_ID`
  in the service file.
- **Google button artwork: download APPROVED and DONE.**
  `.drafts/google-assets/signin-assets.zip` from
  `https://developers.google.com/static/identity/images/signin-assets.zip` (855,303 bytes,
  sha256 `ba884069e12093b06bcfd776915081254a7c95094b80d50be2c5dc6bac1c1da1`; 96 PNG + 24 SVG +
  12 .DS_Store, no executables). Inert per CLAUDE.md §13. Rules (from Google's branding page):
  the standard multicolour G only; light theme #FFFFFF fill, #747775 stroke, #1F1F1F text;
  dark theme #131314 / #8E918F / #E3E3E3; neutral #F2F2F2 with no stroke; Google Sans Medium;
  "Sign in with Google" / "Continue with Google"; at least as prominent as other third-party
  sign-in. Use it in the Google-sign-in wiring unit (after the client IDs arrive).
- **README wording:** keep the accurate version (the full local gate), not "reviewed line by line".

**RELAY DEPLOYED BY THE FOUNDER, 2026-09-18: production = `9952f95`** (verified: `/version` shows
commit 9952f95f86c0f069cb8b0e74659e97eb88a03ca1; server/, core/ and Cargo.lock are identical to that
commit; schema v2 → v3 migrated; backup of the pre-upgrade DB at `/var/lib/skipti/backup-69aeb87/`).
Both Google client IDs are set in the systemd unit, and `POST /v1/auth/google/start` answers 200
(it refuses with no IDs). **So D-130/D-131's relay work is LIVE:** Apple sign-in (nonce) can be
tested end to end once the founder enables the App ID capability (ask him to confirm), and Google
works once the apps are wired (D-138).
- **DEPLOY-ORACLE.md fix owed (my runbook mistake, found in this deploy):** `sudo cp -p
  /var/lib/skipti/relay-vault.db* …` fails because the `*` is expanded by the ubuntu shell,
  which cannot read the 700 directory. Use `sudo sh -c 'cp -p /var/lib/skipti/relay-vault.db* …'`.
  Add an "Updating a running relay" section (build first, then stop → back up → install →
  daemon-reload → start → /version). Fix §7's `SKIPTI_GIT_COMMIT=$(git rev-parse HEAD)`, which
  sits in an "on the instance" block where no git checkout exists: take the hash on the Mac.
  The founder's unit file predated the commented Google lines, so the runbook should say to ADD
  them under SKIPTI_APPLE_AUDIENCE. Do this in D-137 or as a small docs commit.

**GOOGLE CLIENT IDS RECEIVED (2026-09-18; public identifiers, not secrets):**
- Web: `403030719865-7bic8ot5edm533n9gi3j8hthiv0c6bmt.apps.googleusercontent.com`
- iOS: `403030719865-pmgfvj4eo4l73bao8lqblqmpera4augj.apps.googleusercontent.com`
  (callback scheme `com.googleusercontent.apps.403030719865-pmgfvj4eo4l73bao8lqblqmpera4augj`)
- Android clients (upload-key SHA-1, and "Android Debug" with the debug-key SHA-1) exist in
  Google Cloud for com.vardrlabs.skipti. The Play App Signing client comes later.
- The go-live plan, and the two relay `Environment=` lines for the founder, are in
  `.drafts/google-golive/NOTES.md`.

**UNIT ORDER (D-numbers are assigned in the order units are RECORDED):** D-137 gate speed
(`.drafts/d137/`), then D-138 Google sign-in go-live (`.drafts/google-golive/`), then D-139 iOS
27 adoption (`.drafts/ios27/`; it was called "D-137" in BUILD-LOG D-135's row, §3 and residue,
and in CLAUDE.md §10.1 before the renumbering. Correct those in place with "(Corrected D-137:
…)" when D-137 is recorded).

**NEXT STEPS, in order:**
1. **Docs commit: DONE in the commit after `26d9590`** (CLAUDE.md §6 CI-locally, §12, §13; README authorship). Was: `python3 .drafts/docs/apply_docs.py
   --check`, then without `--check`; `git diff` CLAUDE.md README.md; stage those two plus
   docs/WORKLOG.md; commit "docs: CLAUDE.md §12 no attribution, §13 Developer Tools rules;
   README authorship [skip ci]" with NO trailer; push; `gh run list --limit 3` (expect no run).
2. **D-137 — gate speed** (`.drafts/d137/NOTES.md` has the full design). Per-leg timing in
   `scripts/gate.sh` (print after the evidence block so recorded evidence is unchanged). The
   pre-commit hook skips only the heavy legs, and only when the staged gate-relevant inputs
   plus `rustc -V`/`cargo -V` hash to exactly what a passing 17/17 full gate recorded
   (computed at gate start and end). Sabotage rows (a)–(e) are listed there. Baseline:
   `.drafts/gate-timing/` (before Developer Tools: clippy 16m37s; test run 12m44s for ~10 s of
   tests). After it: the D-136 full gate took 20m46s (D-135's took 40m). Re-measure per leg.
   Also fix gate.sh's stale "(~10s warm)" header.
3. **D-138 — Google sign-in go-live** (`.drafts/google-golive/NOTES.md`): the IDs are in hand; the relay
   needs the two Environment lines (founder). Then **D-139 — iOS 27 adoption** (`.drafts/ios27/NOTES.md`): the pill-clipped list-row buttons
   (inventory there), toolbar `.topBarPinnedTrailing` / `visibilityPriority` behind `#available(iOS
   27, *)`, item-binding dialogs, and clearer failure messages from RecordSyncTests' Tasks;
   verify on iOS 27 and 26.5.
4. Carried from earlier (the founder's to act on, see notes below): deploy the relay (production
   is still on 69aeb87; deploy D-131's `91a84a1` or later, never D-130's `04efa47`); Google Cloud
   OAuth client ids (Web + iOS); the Sign in with Apple capability on the App ID; the logo
   decision (Google's signin-assets.zip download needs his explicit yes).

**Tools:**
- `.drafts/common/record_unit.py` records a unit from fresh artifacts, refuses stale data, and
  has `--jvm-carried-forward` for units with no Android change.
- Sabotage runners: `.drafts/d135/sabotage_ios.py` and `.drafts/d136/sabotage_android.py`.
- Emulator helper: `.drafts/d136/emu.sh`.
- Apply scripts: `.drafts/d136/apply_d136.py` and `.drafts/docs/apply_docs.py`.

---

### Earlier NOW notes (2026-09-14 … 2026-09-18), kept for the record


**Last shipped:**
- D-128 — iOS product shell — `b077e95`.
- `CLAUDE.md` + this file — `b596f41`; verified and corrected — `37fc422`.
- D-129 — GUARANTEES #6 amended for sign-in providers (ADR-0035) — `a0ee677`.
  Docs only.
- D-130 — relay Google sign-in — `04efa47`. Full gate 17/17 (cargo test 314;
  iOS 114 executed / 8 skipped; JVM 236 carried forward); sabotage 12/12.
  **NOT deployed.** Everything else is in BUILD-LOG's D-130 row, §3 and §4.
- WORKLOG hand-off — `39ce321`.

**Shipped: D-131 — relay nonce for Sign in with Apple — `91a84a1`** (full gate
17/17 on re-run after RUSTSEC-2026-0285 → rustls 0.23.45; cargo 322; iOS 115/8
skipped/0 failures; sabotage 7/7; pushed; no Actions run). **This is the commit
the founder deploys, not D-130's `04efa47`** (that one carries vulnerable rustls).

**Shipped: D-132 — iOS sign-in buttons — `e1c71e7`** (full gate 17/17; iOS 124 / 8
skipped / 0 failures; sabotage 8/8; simulator checked light + dark; pushed; no
Actions run). Apple's button is live in SignInScreen. The Google flow is built
and HIDDEN until the iOS client id and Google's mark exist.

**Shipped: D-133 — Android `play` Sign in with Google — `b9554c0`** (full gate 17/17;
JVM 250 = 125 × 2 with provenance stated; sabotage 9/9 on run 2; dex proof: direct
has 0 Google types; launch smoke clean; pushed; no Actions run). The button is
hidden on both platforms until the Web/iOS client ids and a logo decision exist.

**Update 2026-09-16: toolchain changed and scratchpad lost.** macOS 27 and Xcode 27.0
(27A266a, Swift 6.4, iOS 27 SDK) installed themselves. The reboots wiped `/private/tmp`, so the
session scratchpad is GONE: the D-135 sabotage runner, recorder and record templates, and
every D-136 draft. D-135's edits are safe in the working tree. Drafts now live in
`.drafts/` at the repo root (local-only, listed in `.git/info/exclude`).
- The D-135 hosted run on the OLD toolchain (iOS 26.5 sim, 2026-09-14): 131 executed, 8
  skipped, **1 failure**, `ExportFlowTests.testLedgerStoreSnapshotRoundTripsThroughCore`
  (expected 1 group, got 0). That test passed before only because export SEEDED the fixture
  group; the group it created was never stored. Fixed: it stores a real group through the
  group store and asserts that exact id. All 7 GroupStoreTests passed.
- **D-135 GREEN on Xcode 27** (2026-09-17T03:07Z, iPhone 17 iOS 27.0 `AFAF4221`): 132
  executed, 8 skipped, 0 failures; the Release build (generic iOS) succeeded; app 9,332 KiB.
  +1 test: `ExportFlowTests.testExportingAnEmptyLedgerSeedsNothing`. `ensure-ios-sim.sh` now
  picks the newest runtime (`SIM_OS=26.5` pins the old device). Swift 6.4 adds a new warning:
  13 unused unstructured throwing `Task`s in `RecordSyncTests`, for D-137 to fix.
  (Corrected 2026-09-18: this said 11 and "such a test can swallow a throw". Each Task
  awaits `restoreFromRelay` and then fulfils an expectation, so a throw fails the test
  by timeout; it cannot pass vacuously. The fix is a clearer failure message.) Sabotage S1–S7 running (`.drafts/d135/sabotage_ios.py`).
  D-136 drafts recreated in `.drafts/d136/`.
- **D-135 sabotage 7/7** (2026-09-17, iOS 27 sim). Found on the way: (1) Xcode 27 spends
  600 s collecting a simulator sysdiagnose after ANY failing test, so the first S1 looked
  hung; fixed with `-collect-test-diagnostics never` in the runner and in `make ios-app`,
  so a red gate reports at once. (2) My S2 expectation was wrong: the placeholder test
  also depends on legacy-Ada adoption, and failing it was correct; the expected set was
  corrected and S2 rerun (first run kept). (3) After a sabotage run, the built app on disk
  still carries the LAST row's sabotage: rebuild before any visual check. Small edits
  before run 2: GroupCopy messages, PeopleScreen clears text only on success, and
  `defaultStore()` in Balance and Add. Recorder rebuilt in `.drafts/common/record_unit.py`
  (negative controls refuse a skips gate and `exit 1`). Next: simulator checks on iOS 27
  (fresh install + onboarding) and on iOS 26.5 (founder's ledger: migration), then the
  full gate.
- **D-135 verified on both simulators (2026-09-18).** iOS 27 fresh install: onboarding →
  "Nothing here yet / Create a group" → Goa (Tim; Rahul, Priya) → Add (Paid by Tim (You),
  INR by default) → Balance ₹600 → Settle Rahul → Balance ₹300 (settlements now count) →
  group list, People, duplicate-name copy (the typed text is kept). iOS 26.5, founder's
  ledger (vault backed up to `.drafts/d135/founder-vault-backup/`, sha256 0c6a1c4b…): "Add
  your name" → Tim → his Goa Trip keeps every record and balance, with "PAID BY TIM (YOU)"
  on the fixture-Ada rows. More fixes from looking: the "dMMMmy" date template (minute,
  since D-54) with test and S8; iOS 26+ pill-clips clear list rows (New group moved to a
  bottom bar, Add another person and Add person put in-card); Add defaults to the group's
  currency. Sabotage 8/8. Hosted 133/8/0. CLAUDE.md updated (Xcode 27, both sims, `SIM_OS`,
  `.drafts/`, the diagnostics flag, the pill trap). His ledger has two identical "Dinner
  in Goa" records from Sep 7 (old seeding era), left untouched. NEXT: full gate → record →
  commit D-135 → apply D-136 (`.drafts/d136/apply_d136.py --check` passes).
- **D-135 FULL GATE PASSED 17/17, 0 skipped** (2026-09-18T05:07:22Z over `b446ffe`; cargo 327,
  iOS 133/8/0 on iOS 27, JVM 250 carried forward: no Android input changed, so Gradle
  reused the D-134 XML, and the recorder now says so explicitly). BUILD-LOG recorded
  (D-01..D-135 contiguous). Committing; the pre-commit fast gate runs because `Makefile`
  is staged.
- (Earlier plan line:) Next: rebuild on Xcode 27 against the iOS 27 simulator (a new iPhone 17, `AFAF4221-…`; the
  old `EB725812-…` is iOS 26.5 and holds the founder's ledger, which is still the
  migration check). Then rewrite the sabotage runner and recorder, and finish D-135.
- Founder (2026-09-16): test on the new Xcode and use the iOS 27 design elements. Xcode 27
  ships `swiftui-whats-new-27` (in `IDEIntelligenceChat.framework/Resources`): @State is now
  a macro (a compile risk); new toolbar APIs (`.topBarPinnedTrailing`, `visibilityPriority`,
  `ToolbarOverflowMenu`, `toolbarMinimizeBehavior`); alert/confirmationDialog with an
  `item:` binding; swipe actions outside List; `reorderable()`. Plan: D-137, iOS 27 adoption,
  gated `#available(iOS 27, *)` (deployment target stays 17.0) and kept to system chrome
  per ADR-0023.

**In flight (2026-09-14): D-135 is being applied.** D-134 is pushed (`ef323fa`, WORKLOG
`b446ffe`; no Actions run). Before applying, the drafts were amended:
(1) names resolve per member with distinct "Member N" placeholders, because a repeated
"Member" would make core refuse the group (new test and sabotage row S6); (2) iOS
Balance now passes the loaded settlements (it passed `settlements: []`, so a settled
debt stayed on Balance). Also recorded for §4: groups do not sync, so self must travel
once they do; import binds self only on proof surfaces. Android `writeSnapshot` drops
names (fixed in D-136). Details: scratchpad `d135/NOTES.md`, `d136/NOTES.md`.

**Shipped: D-134 — groups and people, core half — `ef323fa`** (full gate 17/17;
cargo 327; sabotage 7/7; bindings regenerated and staged before the gate).

**Shipped: D-135 — iOS groups and people — `eb5619a`** (full gate 17/17; iOS 133/8/0 on
Xcode 27 / iOS 27; sabotage 8/8; seen on the iOS 27 and iOS 26.5 simulators).

**Founder question (2026-09-18): why do checks take 30+ minutes, and can they be sped up?**
Measured so far: D-135's full gate took 40 min (04:27–05:07Z), and its legs after the
release sweep (bindings, kotlinc, audit, deny, SBOM, ios-app, android-app, android-test)
took ≤7 min of that. The warm pre-commit FAST gate took 15 min with no Rust change at all,
so the time is not recompiling. **MEASURED (warm cache, no Rust change): clippy 16m37s (rechecked 1 crate), cargo test
build 3m25s (2 crates), cargo test run 12m44s for ~10 s of actual tests in 24 binaries (~30 s
per binary LAUNCH), fmt 0 s, wasm 0 s.** Cause, from `sample` stacks: the compiler waits in
`dlopen` → dyld `mapSegments` → `fcntl` (code-signature registration) while `/usr/libexec/syspolicyd`
runs at 45–64% CPU; every build product carries `com.apple.provenance`, so macOS
Gatekeeper/XProtect assesses each proc-macro dylib load and each new test executable
launch. `spindump` also ran at 99% for 25+ min. The machine (M4, 10 cores, 16 GB) is not
the bottleneck. **Fix is the founder's** (a security setting): System Settings → Privacy
& Security → Developer Tools → enable Claude (and Terminal/Xcode if he builds there);
`sudo spctl developer-mode enable-terminal` shows the pane if it is missing. Mine, next
(D-137, gate speed): skip the pre-commit fast gate's heavy legs when a full gate has just
passed on byte-identical gate-relevant inputs, and print per-leg durations. iOS 27
adoption becomes D-138. Raw data: `.drafts/gate-timing/`.

**D-136 progress (2026-09-18):** JVM 272 = 136 × 2, 0 failed on first run; APKs built.
Sabotage 10/10. A6's first expected set was wrong: the import test asserts the placeholder
"Member 3", so it rightly went red too. Corrected and rerun; run 1 is kept in
`.drafts/d136/results_android_run1.json`. Emulator (skipti-shots; both apps' data backed up to
`.drafts/d136/emu-backup/` first): the play app UPGRADED over its old vault shows "Add your
name" → Tim → Goa Trip keeps its $4.20 record, the payer reads "Tim (You)", Settle → $0.00;
the direct app FRESH (cleared) → onboarding → "Nothing here yet / Create a group" → Goa (INR;
Tim; Rahul, Priya) → Add (PAID BY Tim (You), INR) ₹900 → Balance ₹600 → Settle Rahul → ₹300 →
group list ✓ → People, "priya" refused with the copy while the text stays. Screenshots in
`android/artifacts/d136-*.png`. Asymmetry for §4: the Android group chip is compact in the
Material button font, while iOS uses a full-width secondary button. NEXT: full gate → record → commit.

**In flight (2026-09-18): D-136 APPLIED** to the tree over `779c0a3` (`apply_d136.py`: 49
anchored edits, 17 buildExpense + 5 recomputePlan call sites rewritten, GroupScreens.kt and
GroupStoreTest.kt added). `make android-test` + `make android-app` running; then sabotage
A1–A10 (`.drafts/d136/sabotage_android.py`), emulator check (skipti-shots is booted;
`.drafts/d136/emu.sh`), full gate, record, commit.

**Next unit: D-136 — Android groups and people** (twin). Everything is drafted in
`.drafts/d136/`: `apply_d136.py` (`--check` passes: 49 anchored edits, 17 buildExpense
call sites, 5 recomputePlan call sites, 2 new files), `GroupScreens.kt`, `GroupStoreTest.kt`
(10 tests), `sabotage_android.py` (A1–A10, anchors verified against the planned text),
`emu.sh` (emulator helper), `NOTES.md`. After it: D-137, iOS 27 adoption (`.drafts/d137/NOTES.md`).

(Old plan line follows:) **Next unit: D-135 — iOS groups and people.** Everything is drafted in the
scratchpad `d135/`: GroupStore, GroupSheets, GroupPicker, GroupStoreTests,
apply_d135.py (28 edit + 16 pbxproj anchors dry-checked), sabotage_ios.py
(5 rows, anchors verified). D-136 is the Android twin (findings in `d136/NOTES.md`).

(Old plan line follows:) **Next unit: D-134 — groups and people ("add by name, link later")**; its
scoping is under "D-129 — groups and people: findings so far" (renumbered: that
design never shipped as D-129). Report sent to the founder with the Android logo
finding. (Old D-133 plan line follows:) Android `play`: Sign in with Google via Credential
Manager (design under "Google sign-in design"). Still waiting on the founder
for: the deploy of `91a84a1`+, the Google Cloud clients, the Sign in with Apple
capability, and permission for the Google mark download.

**D-133 STARTED (2026-09-14): Android `play` Sign in with Google.**
Report sent to the founder: deploy HEAD `f8f8cdd` (the relay is identical to
`91a84a1`, verified by an empty `git diff 91a84a1 HEAD -- server/ core/
Cargo.*`), then the Google Cloud clients, the Apple capability and the logo
permission. The iOS Google button stays hidden until he answers.
Reading done:
- Android SignInScreen: identifier field → "Use a passkey" (accent Button) plus
  the OTP TextButton fallback; `PasskeyCeremony` is the Credential Manager
  pattern to mirror.
- The only flavour source sets are manifests (`src/play`, `src/direct`).
- `IdentityTest.kt` is the stub-transport suite to extend.
**Findings before adding anything:**
- `googleid:1.2.0` depends on `androidx.credentials:1.6.0-beta01`, so it would
  silently lift the app's pinned 1.5.0 to a beta.
- `credentials-play-services-auth:1.5.0` pulls at runtime
  `play-services-auth:21.1.1`, `play-services-fido:21.0.0`,
  `play-services-auth-blockstore:16.4.0` and
  `play-services-identity-credentials:16.0.0-alpha02` (an ALPHA). Any of these
  may add providers, services or activities to the merged manifest. ADR-0035
  constraint 1 (never contact Google at launch) means the merged manifest must
  be diffed before and after.
- Google's branding rules apply to the Android button too, so it stays HIDDEN
  (same pending permission as iOS). The Web client id is also absent.
- **No alpha-free set exists.** `credentials-play-services-auth:1.6.0` (the
  current stable) still pulls `play-services-identity-credentials:16.0.0-alpha08`
  at runtime (1.5.0 pulled alpha02). Google ID sign-in cannot be built from
  the documented libraries without that alpha. ADR-0035 constraint 5 already
  names this library, so this is a fact to record, not a new fork. Plan: the
  stable 1.6.0 line (`credentials` 1.6.0, `credentials-play-services-auth`
  1.6.0, `googleid` 1.2.0, whose beta requirement 1.6.0 satisfies), with the
  alpha stated in the rule-4 comment.
- **SUSPECTED GATE-INTEGRITY DEFECT, being checked:** the play merged manifest
  that `find … | head -1` returned is dated **Aug 31**.
  `scripts/check-play-manifest.sh` resolves its subject with `ls -t
  merged_manifests/*/play*/AndroidManifest.xml`, which cannot match
  `playDebug/processPlayDebugManifest/`. It then falls back to `find | head -1`,
  which is NOT newest-first. If the fresh build writes a different path, the
  gate's Play-manifest leg has been approving a stale artifact. Also, my first
  component listing used a single-line grep and missed multi-line tags; use
  an XML parser.
- **Manifest question, part 1:**
  - Every intermediate manifest dates from Aug 31 (play debug) or Aug 28 (direct
    and release), while the play APK dates from Sep 7. That is consistent with
    Gradle's up-to-date skip (no manifest input changed), so an old mtime is
    not by itself staleness.
  - The script's resolution IS fragile. Its `ls -t merged_manifests/*/play*/`
    glob can never match (zsh: "no matches found"), so it is dead code. Its
    `find … | head -1` fallback is neither newest-first nor pinned to a
    variant: it returned `packaged_manifests/playDebug/…` this time, but a
    `playRelease` (Aug 28) manifest is equally eligible.
  - **Checked: no false approval.** The manifest packaged inside the Sep 7
    play APK (`aapt2 dump xmltree`) and the Aug 31 merged manifest the
    script read are IDENTICAL: 10 entries each, none only-in-one. So the gate
    has been checking the truth, through a lookup that works by luck. Fix in
    D-133: resolve `merged_manifests/playDebug/processPlayDebugManifest/AndroidManifest.xml`
    explicitly (the variant `make android-app` assembles) and fail if missing.
- **Baseline play merged manifest** (XML-parsed):
  - uses-permission: INTERNET, USE_BIOMETRIC, USE_FINGERPRINT,
    DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION.
  - activity: MainActivity.
  - provider: androidx.startup.InitializationProvider (meta-data: emoji2
    EmojiCompatInitializer, lifecycle ProcessLifecycleInitializer,
    profileinstaller ProfileInstallerInitializer).
  - receiver: ProfileInstallReceiver.
- **D-133 design, as being written (2026-09-14):**
  - Gradle: `credentials` 1.5.0 → 1.6.0. `playImplementation`
    `credentials-play-services-auth:1.6.0` and `googleid:1.2.0`, with a rule-4
    comment naming the alpha transitive.
  - `src/play/java/.../GoogleCredential.kt`: Credential Manager +
    `GetSignInWithGoogleOption.Builder(serverClientId).setNonce(nonce)`, then
    `GoogleIdTokenCredential.createFrom(credential.data).idToken`. Cancel →
    `CeremonyCancelled`; no account or other failure → `CeremonyUnavailable`;
    `SUPPORTED = true`.
  - `src/direct/java/.../GoogleCredential.kt`: the same class shape, no Google
    import, `SUPPORTED = false`, and `idToken` throws `CeremonyUnavailable`.
  - `main/.../GoogleSignIn.kt`: `GoogleSignInConfig` (`WEB_CLIENT_ID = null`,
    `BRAND_MARK_BUNDLED = false`, pure `isAvailable`) and
    `signInWithGoogle(context, transport, webClientId)`.
  - IdentityService: `startGoogleSignIn()` (POST `v1/auth/google/start`, no
    body, no bearer; an empty or missing nonce is BadResponse) and
    `signInWithGoogle(idToken, nonce, deviceLabel, syncDeviceId)` (POST
    `v1/auth/google`). The new bodies are named `SignInNonceBody` and
    `GoogleSignInBody`. WRITTEN.
    Read first: `RelayHTTPRequest.body` is `ByteArray? = null`, and
    `SyncWire.json = Json { ignoreUnknownKeys = true }`, so encodeDefaults is
    false and an absent `syncDeviceId` (default null) is omitted, as iOS does.
  - `src/test/.../GoogleSignInTest.kt`: 6 tests in the SHARED set (both
    flavours) covering start, empty/missing nonce, failure mapping, the body
    shape with a device and without, a partial body rejected, and availability.
    WRITTEN.
  - **First build FAILED in 4 s, before any Kotlin compiled:**
    `build.gradle.kts:210:5: Unresolved reference 'playImplementation'`. The
    Kotlin DSL generates typed accessors before `android { productFlavors }`
    runs, so flavour configurations have none. Fixed with
    `add("playImplementation", "…")` (asserted edit), plus a comment saying why.
    The sabotage runner's A8 anchor was updated to match.
  - **Rebuild: `make android-app` rc=0** (BUILD SUCCESSFUL in 20 s; both flavours
    compiled; PLAY MANIFEST CHECK OK against the pinned playDebug file, 21:07).
  - **Merged-manifest diff against the pre-D-133 baseline:**
    - playDebug gained 8 entries:
      - activities, all `exported=false`: credentials playservices
        `HiddenActivity`, `IdentityCredentialApiHiddenActivity`; gms
        `SignInHubActivity`, `GoogleApiActivity`;
      - services: `CredentialProviderMetadataHolder` (exported=false) and gms
        `RevocationBoundService` (**exported=true**; its permission is being
        checked);
      - meta-data: `com.google.android.gms.version`,
        `androidx.credentials.CREDENTIAL_PROVIDER_KEY`.
    - **No provider, no receiver, no startup initializer, no new permission.**
      Statically, nothing runs without a tap (constraint 1); a runtime smoke
      test comes next.
    - directDebug: no Google entry. Its only difference from the (play)
      baseline is its own `com.vardrlabs.skipti.direct.DYNAMIC_RECEIVER…`
      permission, which is expected. The file is still dated Aug 28 (inputs
      unchanged).
  - **Size question being checked:** play APK 53,132 KiB vs direct 53,128 KiB, a
    4 KiB gap, although both grew about 1 MB over the Sep 7 play APK. "Direct
    has no Google code" must hold in the DEX, not only on the test classpath.
    Counting Google class names in each APK's dex.
  - **`make android-test` rc=0: TOTAL 250 executions = 125 tests × 2 variants, 0
    failed** (before D-133: 236 = 118 × 2, so +7 = GoogleSignInTest 6 +
    GoogleFlavourTest 1, per variant). Fresh result files: 32 of 32.
  - **Dex proof of constraint 5** (distinct type names in each APK's
    classes*.dex):
    - play: `googleid` 14 · `gms` 1964 (auth 265) · `androidx.credentials.playservices` 257.
    - direct: **0 · 0 · 0**. The direct build ships no Google sign-in code; this
      is proven in the installed dex, not only on the test classpath.
    - Uncompressed dex: play 34.45 MB vs direct 32.66 MB. Checking compressed
      sizes for the tiny APK-size gap.
    - **My first count was WRONG:** `strings | grep '^L…'` missed most names,
      because a dex string's ULEB128 length byte, when printable, is glued to
      the front of the line. It reported googleid 0 and gms 7 for play. The
      recount used unanchored `grep -aoE 'Lpkg/…;'` with dedup.
  - `RevocationBoundService` is exported, but binding requires
    `com.google.android.gms.auth.api.signin.permission.REVOCATION_NOTIFICATION`;
    it is Play services' revocation callback.
  - Emulator `skipti-shots` booted (16.8 s); its image has NO Google Play services.
  - **Launch smoke of the play APK (constraint 1 at runtime):**
    - `am start -W`: Status ok, COLD, 538 ms; app pid 2115 alive afterwards.
    - `dumpsys activity services`: **nothing** running for the app.
    - Activities: only `MainActivity` resumed.
    - logcat, 128 lines (26 from the app pid): **zero** lines matching
      GooglePlayServices / GoogleApi / gms / SignInHub / RevocationBound /
      GoogleCredential / credential / FATAL / AndroidRuntime. Play services
      is absent here, so any gms code running at launch would have logged
      "Google Play services is missing". None did.
    - Screenshot: scratchpad `d133/smoke-play-launch.png`.
  - **APK entry diff** (zipfile, compressed sizes):
    - play's entries total 32,740,235 bytes vs direct's 31,902,083 (+838 KB):
      64 entries only in play (incl. `classes7.dex`), 8 only in direct, 130
      differing.
    - The ~5.8 KB file-size gap is NOT a content measure. Both files are also
      ~20 MB larger than their entries' compressed total, which I have not
      explained, so no cause is claimed. The dex type-name proof is the
      evidence.
    - The first screenshot caught only the launch splash (a faint centred
      circle); a second one is being taken.
  - **FINDING for the founder:** Google's official sign-in button assets already
    ship INSIDE `play-services-auth`: `res/drawable-*/googleg_standard_color_18.png`
    (the "G" mark) and `common_google_signin_btn_{icon,text}_{light,dark}_*`
    backgrounds. For ANDROID, the branding question might be answered by
    Google's own library resource, with no separate download. That is his
    decision (branding and design), so report it and do not act. The button
    also stays hidden for lack of a Web client id.
  - **Launch, settled:** a second screenshot plus `uiautomator dump` show the
    Balance screen rendered ("GOA TRIP · 4 MEMBERS · SETTLED IN USD", "$4.20",
    tabs Balance / Expenses / Add / Settle); pid 2115 still alive. The only
    later `AndroidRuntime` lines come from uiautomator's own process (uid 2000),
    not the app.
  - **Sabotage matrix RUNNING, with a RUNNER DEFECT of mine.** Rows A1–A4 each
    produced fresh JUnit XML for only ONE variant; that variant failed exactly
    as expected, and the other variant never ran ("expected_but_green", fresh
    false). Cause: `make android-test` runs `./gradlew
    :app:testPlayDebugUnitTest :app:testDirectDebugUnitTest` WITHOUT
    `--continue`, so Gradle stops at the first failing task. The gate is not
    weakened (any failure still fails it), but the matrix must prove BOTH
    variants go red, so the runner needs `--continue`. The current run is left
    to finish because every row restores its file; killing it mid-row could
    leave a sabotage in the tree.
  - **Run 1 finished: 3/9 OK** (A6, A7, A9). Every MISMATCH is the one-variant
    defect: A1/A2/A4/A5 ran only play, A3/A8 only direct, and each time the
    variant that ran failed exactly as expected. Results are archived in the
    scratchpad at `d133/run1/`. The tree was verified restored: no sabotage
    string anywhere; the `syncDeviceId: String?,` hit at IdentityService.kt:156
    is the function PARAMETER, not the data-class field.
  - **Runner fixed:** it now calls `zsh -lic 'cargo build -p skipti-core -q && cd
    android && ./gradlew …Play… …Direct… --continue'`.
  - **Run 2 STARTED** (all 9 rows). Until it ends, the in-tree Android sources
    carry each row's sabotage in turn: DO NOT edit them.
    **Run 2, row A1: the fix is confirmed.** Fresh results for BOTH variants, and
    both `play::` and `direct::GoogleSignInTest.an_empty_or_missing_nonce…`
    red.
  - **SABOTAGE RUN 2: 9/9 OK** (`ANDROID DONE 9 of 9 OK`). Every JUnit row had
    fresh results for both variants and no compile error:
    - A1–A5 → exactly their GoogleSignInTest test in play AND direct;
    - A6, A8 → only `direct::GoogleFlavourTest`; A7 → only `play::GoogleFlavourTest`;
    - A9 → manifest check exits "merged manifest not found".
    Tree verified restored: no sabotage string; git status shows exactly
    D-133's files.
  - **Emulator shut down; full gate STARTED** (background) → scratchpad
    `d133/gate.log`. NO tracked edits until it finishes.
  - **GATE PASSED 17/17, 0 skipped** (evidence stamp 2026-09-15T01:32:59Z, working
    tree over HEAD `f8f8cdd`).
  - **Finding: the gate log carries NO JVM tally.** It holds only the android-test
    `[PASS]` line and the generic "per-class tally printed" evidence row; the
    TOTAL line make prints is not captured. `record_d133.py` was changed to take
    the tally from the JUnit XML, per variant, with timestamps.
  - **Finding: play's JUnit XML was NOT written by the gate.**
    - direct: 125 tests, 0 failed, 16 files at 21:32:59 (this gate).
    - play: 125 tests, 0 failed, 16 files at **21:15:23**, before the gate
      started.
    - Gradle judged play's test task up-to-date. The last sabotage row (A8)
      only moved `googleid` from `playImplementation` to `implementation`, so
      play's classpath and sources were identical to the restored tree, and
      the gate reused A8's passing play results. That is sound for identical
      inputs, but it is not a gate execution, so play is being re-executed
      with `--rerun` and the record will say where each number came from.
    - CLAUDE.md §7 already warns that Gradle reuses cached results; this shows
      it happening INSIDE a full gate run straight after a sabotage matrix.
  - **Play re-executed:** `./gradlew :app:testPlayDebugUnitTest --rerun` rc=0 in
    10 s; 16 files, 125 tests, 0 failed, all rewritten at 21:34:11. With
    direct's gate run (21:32:59), both variants now carry results produced
    from this exact tree. The §3 draft states the provenance.
  - **Recorder:** negative control (synthetic failed log) rc=1; positive dry run on
    the real gate log rc=0.
  - **BUILD-LOG RECORDED:** D-133 row, §3 (with the JVM provenance paragraph),
    residue, changelog. D-01..D-133 contiguous; the diff adds no placeholder;
    the JVM line reads "250 executions = 125 tests x 2 variants, 0 failed
    (direct XML written 21:32:59; play XML written 21:34:11)". Committing.
  - The D-133 record drafts are in the scratchpad at `d133/final/`. The only
    placeholders not auto-filled are SABOTAGE SUMMARY and SABOTAGE ROWS.
    `record_d133.py` also extracts and requires make android-test's
    `TOTAL … executions = … tests x 2 variant(s), 0 failed` line from the gate log.
  - Sabotage anchors checked against the tree while the rebuild runs: all 9
    match exactly once (A1–A4 IdentityService.kt, A5 GoogleSignIn.kt, A6/A7
    the flavour GoogleCredential.kt files, A8 build.gradle.kts, A9
    check-play-manifest.sh).
  - The sabotage runner is drafted in the scratchpad at
    `d133/sabotage_android.py` (A1–A9, expected sets fixed up front, fresh
    JUnit XML per row, failures attributed per variant).
  - Tests: `src/testPlay/.../GoogleFlavourTest` (classes present) and
    `src/testDirect/.../GoogleFlavourTest` (classes ABSENT), one test each
    under the SAME name. `make android-test` prints "N tests x 2 variants" and
    assumes identical test sets per flavour, so asymmetric flavour tests would
    make that line lie.
  - `check-play-manifest.sh` is pinned to the playDebug merged manifest.
  - No UI in D-133: the button stays hidden (no Web client id, no mark).
  After the Google dependencies are added, diff against this. Any new
  provider, startup initializer, or anything that runs without a tap is a
  constraint-1 question.

(D-132 working record, kept below for detail:) Everything
was prepared in the scratchpad `d132/` while D-131's gates ran, and checked:
- The drafts `GoogleSignIn.swift` and `AppleSignIn.swift`, plus the
  `IdentityService` additions, type-check against the iOS 17 simulator SDK
  (rc=0). A negative control with a deliberate type error failed (rc=1).
- `apply_d132.py` is all-or-nothing, with asserted anchors and unused-ID
  checks. It adds 3 new files, edits IdentityService, the entitlements and
  SignInScreen, and makes 12 pbxproj insertions (A10043–45 / B10112–14).
- `sabotage_ios.py`: 8 rows with the expected red sets fixed in advance; every
  anchor was verified against the drafts (8/8 match once).
- `record-drafts.md`: row, §3 skeleton, §4 residue, commit skeleton.
- Core has no general random-bytes FFI (only `ffi_rule_new_id`), so the PKCE
  verifier and state use SecRandomCopyBytes. They are protocol values, not
  keys, so D-66 does not apply.

**D-132 progress (2026-09-15):**
- `apply_d132.py` rc=0: 3 new files (`GoogleSignIn.swift`, `AppleSignIn.swift`,
  `ProviderSignInTests.swift`); 6 edits across IdentityService, the
  entitlements and SignInScreen; 12 pbxproj insertions.
- Verified: A10043/44/45 appear 2× each and B10112/13/14 3× each, in the
  right sections; `plutil -lint` on the entitlements OK; swift autolink check OK.
- `make ios-app` is running in the background (log in the scratchpad at
  `d132/ios-app.log`).
- **`make ios-app` FAILED, and the defect is mine.** "Build input files cannot be
  found: ios/Skipti/App/Skipti/Relay/GoogleSignIn.swift" (and AppleSignIn.swift).
  The apply script wrote the new references with `sourceTree = "<group>"`, so
  `path = Skipti/Relay/…` resolved under the App group's own folder. I had read
  IdentityService's reference line through `cut -c1-170`, which dropped its
  real `sourceTree`, and the script guessed it. The typecheck could not catch
  this: it compiled scratchpad copies and never read the pbxproj. **Lesson:
  read pbxproj lines untruncated before copying their shape.** Fixing the two
  lines now. The interpolation gate check on the tree is OK.
- **Rebuild after the sourceTree fix: the app target compiled; the test target
  did not.** `ProviderSignInTests.swift:120:34: error: 'async' call in an
  autoclosure that does not support concurrency`: `XCTAssertEqual(try await …)`.
  XCTest's assertion arguments are autoclosures and cannot await. My scratchpad
  typecheck covered only the app files, because the test file needs the built
  module for `@testable import`, so this surfaced only in the real build.
  Fixed (bind with `let`, then assert) in both the tree and the draft, and
  rebuilding.
- **iOS GREEN (2026-09-14 20:32):** `make ios-app` rc=0. XCTest: 124 executed,
  8 skipped, 0 failures (115 + 9 new; all 9 ProviderSignInTests passed). The
  Debug `Skipti.debug.dylib` is dated 20:32. Now: the simulator check.
- **Simulator, light (20:33):** the fresh build (both new strings found in the
  dylib by `strings`). Route: Balance → account glyph (364,84) → hub "No account"
  → Sign in (201,300). The Sign in screen shows:
  - Apple's own black "Sign in with Apple" button, full width at the top;
  - under it, "Apple only confirms it is you. It never sees your ledger." in
    the dim token;
  - no Google button;
  - the email-code form below, with Send code dimmed while the field is empty.
- **Simulator, dark (20:34):** `simctl ui … appearance dark`. `.id(colorScheme)`
  rebuilt Apple's button in its WHITE style; the copy line and the email field
  follow the dark tokens.
  **Defect seen in both schemes:** a stray Form row-separator hairline under
  Apple's button. Fixed with `.listRowSeparator(.hidden)` on the button row and
  the copy row (asserted edit, 2 occurrences). This needs a rebuild and a
  second look, which come BEFORE the sabotage run.
- **Apple tap (20:34):** tapped against the PRODUCTION relay, which lacks the
  D-131 route, so the error copy is expected, not Apple's sheet.
  **Result:** "That was not accepted." (RelayError.rejected = HTTP 401), not
  "could not be read". No Apple sheet opened, so only our relay was contacted.
  **Explained by probe** (curl to our own relay, the same unauthenticated
  request the app made):
  - `/version` → commit `69aeb87`, so **production has neither D-130 nor
    D-131.**
  - `POST /v1/auth/apple/start` → 401 `{}`.
  - `POST /v1/auth/definitely-not-a-route` → 401 too: the relay answers any
    unknown POST under `/v1/auth/` with the uniform rejection.
  So the app behaved correctly for an undeployed route. This resolves once
  `91a84a1` is deployed.
  Simulator appearance restored to light.
- **Rebuild with the separator fix: GREEN (20:35).** `make ios-app` rc=0; 124
  executed, 8 skipped, 0 failures; the dylib is dated 20:35 and contains the new
  copy. Next: relaunch and look (light + dark) for the hairline, then sabotage.
- **Re-check, light (20:36), fresh 20:35 build:** the hairline under Apple's
  button is GONE; the button meets the copy line cleanly and nothing else moved.
- **Re-check, dark (20:37):** white Apple button, no hairline, copy and field on
  dark tokens. **The visual check is COMPLETE** and the appearance is restored to
  light.
- **Sabotage STARTED** (background): `d132/sabotage_ios.py`, 8 rows, each a full
  rebuild and suite run, with results in `d132/results_ios.json`. When it ends,
  `ios/build` holds the S8-sabotaged build: never launch it, and let the gate
  rebuild.
  **S1–S3 OK** (each exactly its one expected test; restores byte-identical):
  - S1 PKCE input altered → `testPKCEChallengeMatchesRFC7636AppendixB`.
  - S2 state check removed → `testCallbackParsing`. The grand total said "3
    failures" because XCTest counts ASSERTION failures, not failed tests: three
    of that test's assertions fail without the state check. Still one red test.
    Say "one test, three assertions" in §3, never "3 failures".
  - S3 scope widened → `testAuthorizationRequestIsTheOpenIDCodeFlowAndNothingMore`.
  - **S4 OK** (redirect path misspelt) → exactly `testRedirectMatchesTheRelaysDerivation`
    and `testAuthorizationRequestIsTheOpenIDCodeFlowAndNothingMore`; grand total
    2 failures; restore byte-identical.
  - **S5 OK** (Apple request asks for `.email`) → exactly
    `testAppleRequestAsksForNothingAndCarriesTheRelayNonce`; 1 failure.
  - **S6 OK** (availability ignores the missing brand mark) → exactly
    `testGoogleIsOfferedOnlyWithAClientIdAndGooglesMark`; 1 failure; restore
    byte-identical.
  - **S7 OK** (`code_verifier` wire key misspelt) → exactly
    `testGoogleStartAndCodeReachTheRelayRoutes`; 1 failure; restore
    byte-identical.
  - **S8 OK** (every error treated as a cancellation) → exactly
    `testOnlyApplesCancelIsSilent`. "2 failures" = one test, two assertions.
  - **SABOTAGE 8/8 OK** (`IOS DONE 8 of 8 OK`). Tree verified restored: no
    sabotage string in GoogleSignIn, AppleSignIn or IdentityService, and the
    state check and brand-mark gate are present.
- **Full gate STARTED** (background), with its log in the scratchpad at
  `d132/gate.log`. NO tracked edits until it finishes. The gate rebuilds
  `ios/build` (which held S8's build).
- Record drafts in `d132/final/` are filled except the auto-filled keys
  (evidence, cargo, iOS total, JVM, gate line).
- **GATE PASSED 17/17, 0 skipped** on the working tree over HEAD `91a84a1`:
  cargo 322; iOS 124 executed / 8 skipped / 0 failures (the log was rewritten by
  the gate); JVM carried forward.
- **Recorder controls:**
  - Negative: `--dry-run` on d131's synthetic PASSED-over-FAILED log gave
    rc=1, refusing on failed legs before reading the iOS log; nothing written.
  - Positive: `--dry-run` on the real gate log gave rc=0.
- **BUILD-LOG RECORDED:** D-132 row after D-131, §3 before the gap register,
  residue after D-130's (f), changelog appended. D-01..D-132 contiguous; the
  diff adds no placeholder.
- Committing now.
  - D-132 record drafts are in `d132/final/`. The only placeholders not
    auto-filled are `<<SABOTAGE SUMMARY>>` and `<<SABOTAGE ROWS>>`.
- `d132/record_d132.py` is written with record_d131's guards. Its BUILD-LOG
  anchors are verified unique: the D-131 row, the end of D-130's residue (f),
  the end of D-131's changelog, and the gap-register heading.
  `inspect` was unavailable, so this was checked by screenshot. Next: dark
  mode, then a tap against the production relay, which lacks the D-131 route,
  so expect the error copy.
- Next: simulator look at SignInScreen (light + dark) BEFORE sabotage, because
  the sabotage runs leave a sabotaged build in `ios/build`. Then
  `sabotage_ios.py`, the full gate, and the record.

(D-131 working record, kept below for detail:)
Started 2026-09-14; the founder said "go ahead with your next set of work".
Why: ADR-0035 constraint 3 says that on both platforms the relay verifies every
ID token against a single-use nonce it issued. D-63's Apple path has none (D-130
§4 residue (e)), so an iOS Apple button built on it would ship a known ADR
violation. The founder has not deployed D-130 yet, so one deploy covers both.
Plan:
- NEW `server/src/nonce.rs`: move `NonceStore`, `NONCE_TTL`, `NONCE_MAX_LIVE`
  and their two unit tests out of `google.rs`, behaviour unchanged.
- `apple.rs`: `AppleAuth` gets its OWN `Mutex<NonceStore>`, so a Google nonce
  can never sign in with Apple. Add `issue_nonce()`. `verify(token, nonce)`
  spends the nonce first, then requires the token's `nonce` claim to equal it.
- `POST /v1/auth/apple/start` → `{nonce}`. `AppleSignIn.nonce` is
  `#[serde(default)]`, so a pre-D-131 client gets the uniform 401 rather than
  axum's 422.
- `apple_http.rs`: every test takes a nonce. New tests: replay, never-issued,
  another attempt's nonce, no nonce claim, a failed attempt still spends, a
  body without a nonce gets the uniform 401, a Google nonce is refused by
  Apple.
- iOS `IdentityService`: add `startAppleSignIn()`, add a `nonce` parameter to
  `signInWithApple`, update `RelayClientTests`. No iOS UI in this unit.
**Progress 2026-09-14:** all planned code WRITTEN.
- NEW `server/src/nonce.rs`. `google.rs` now imports from it.
- `apple.rs`: own `Mutex<NonceStore>`, `issue_nonce`, `verify(token, nonce)`
  spends first, then compares the claim.
- `api_types.rs`: `AppleSignIn.nonce` is `#[serde(default)]`;
  `GoogleNonceOut` renamed `SignInNonceOut`.
- `lib.rs`: `pub mod nonce`, `auth_apple_start`, route `/v1/auth/apple/start`,
  new `new_shared_state_with_providers`.
- `apple_http.rs`: 13 tests, 8 of them new.
- iOS: `IdentityService.startAppleSignIn()`, and `signInWithApple` takes
  `nonce:`. `RelayClientTests` gains `testAppleStartFetchesTheRelaysNonce`, and
  its three Apple tests plus the E2E test now pass a nonce.
**Relay GREEN:** `cargo test -p skipti-server` — lib 42 (the 2 store tests
moved from google to nonce, so still 42), `apple_http` 13, `google_http` 17,
every other suite unchanged; clippy `-D warnings` clean.
**iOS GREEN:** `make ios-app` rc=0. XCTest: 115 executed, 8 skipped, 0 failures
(114 + `testAppleStartFetchesTheRelaysNonce`).
**Sabotage RUNNING** in the background, scratchpad `d131/`:
- `sabotage_relay.py` → `results_relay.json`.
- `sabotage_ios.py` → `results_ios.json`, rebuilding the full suite per row.
  **iOS DONE 2/2 OK**: I1 → only `testAppleSignInSendsSnakeCaseAndDecodesTheSession`,
  I2 → only `testAppleStartFetchesTheRelaysNonce`. Each total was "115 tests, 8
  skipped, 1 failure"; no compile error; restores byte-identical.
  **Relay runner defect (mine, found on row A1):** every failed test is
  attributed to binary "?", so the verdict column reads MISMATCH. Suspected cause:
  cargo prints "Running …" on STDERR while test lines go to STDOUT, and the
  runner joins stdout+stderr, so no test line follows its "Running" line.
  A1's red set is exactly one test, `a_token_signs_in_only_with_its_own_attempts_nonce`,
  but `google_http` has a test with the same name, so attribution must be
  re-derived from the saved logs (`d131/reeval_relay.py`) before any verdict
  is trusted. Do NOT read `results_relay.json`'s verdicts.
  **Confirmed on the log:** cargo's stdout test blocks run in the same order as
  its stderr "Running" lines (lib, apple_http, google_http).
  **RELAY 5/5 OK after `reeval_relay.py`**: A1 → own-attempt; A2 → the 4
  expected; A3 → no-claim; A4 → failed-attempt; A5 → no-nonce-body. All in
  apple_http, no compile error, 3 blocks each, restores byte-identical. Tree
  verified clean of sabotage strings.
  **SABOTAGE TOTAL 7/7** (5 relay + 2 iOS).
**Full gate STARTED 2026-09-14** (background) → scratchpad `d131/gate.log`.
NO tracked edits until it finishes. `ios/build` held the I2-sabotaged build
until this gate rebuilds it.
**GATE FAILED 15/2 (2026-09-14T19:41Z)** — `cargo audit` and `cargo deny`
(advisories). Cause, NOT D-131 code: a RustSec advisory published after
D-130's green gate. rustls 0.23.43 (via `ureq 2.12.1` → skipti-server)
accepts handshake messages spanning key changes (RFC 8446 §5.1),
GHSA-2mjx-qc3c-rqvc. Fix: rustls >= 0.23.45, a lockfile-only patch update of
an existing transitive dependency, so my call, not a fork. Every other leg
PASSED: iOS 115 executed / 8 skipped / 0 failures (fresh log 15:41); JVM XML
dated 09-07 → carried forward; wasm, bindings, SBOM and both Android legs green.
Artifacts: `scripts/gate-artifacts/20260914T194121Z-cargo-audit.log`, `…194122Z-cargo-deny-check…log`.
**Note:** D-130's commit `04efa47` ships the same vulnerable rustls; its
deploy commands must be superseded by D-131's commit, which carries the
update. Tell the founder.
**rustls UPDATED:** `cargo update -p rustls` changed only `Cargo.lock`, 2 lines
(0.23.43 → 0.23.45 plus its checksum); nothing else moved. `cargo audit` rc=0
(290 crates); `cargo deny check`: advisories, bans, licenses and sources all ok.
**GATE 2 PASSED 17/17, 0 skipped** (2026-09-15T00:05Z, working tree on HEAD
`39ce321`): cargo test 322 (314 + 8 new in apple_http); iOS 115 executed / 8
skipped / 0 failures (fresh log); JVM carried forward (XML 09-07).
**BUILD-LOG RECORDED** by `d131/record_d131.py`: D-131 row after D-130, §3
block before the gap register, gap (e) marked CLOSED, changelog appended.
`check-buildlog`: D-01..D-131 contiguous. No placeholder leaked; the one `<<`
in BUILD-LOG is D-103's, already in HEAD.
Recorder controls, run before it touched the record:
- **Two shape bugs fixed.** A passing gate prints no "failed" count, and the
  evidence extent runs from GATE PASSED to `make android-test`.
- **A guard against failed legs.** A synthetic PASSED relabel of gate 1's log
  was refused (rc=1).
- **A positive control.** D-130's real passing block was accepted (rc=0).
**Committing** in the background with `d131/commit-msg.final.txt`; the
pre-commit fast gate runs, and the log is `d131/commit.log`.
- NOTE: afterwards `ios/build` holds a build of the LAST sabotaged state (I2).
  The full gate rebuilds it; never launch that build for a visual check.
**Sabotage plan (expectations fixed BEFORE running):**
- A1 claim not compared → `a_token_signs_in_only_with_its_own_attempts_nonce`.
- A2 spend replaced by `true` → replay, never-issued, failed-attempt,
  google-nonce.
- A3 `None =>` accepted → `a_token_without_a_nonce_claim_is_refused`.
- A4 spend moved after a successful claim match → `a_failed_attempt_still_spends_its_nonce`.
- A5 `serde(default)` removed → `a_request_without_a_nonce_gets_the_uniform_rejection`.
- iOS I1 `case nonce` key misspelt → `testAppleSignInSendsSnakeCaseAndDecodesTheSession`.
- iOS I2 empty-nonce guard removed → `testAppleStartFetchesTheRelaysNonce`.
- No sabotage isolates the cross-provider test; A2 covers it.
**Apple's nonce claim — source checked 2026-09-14:** WWDC20 "Get the most out of Sign in with Apple" (https://developer.apple.com/videos/play/wwdc2020/10173/): the nonce "will be returned to you embedded in the identityToken" and should be verified "to be the same nonce you generated"; `ASAuthorizationOpenIDRequest.nonce` (https://developer.apple.com/documentation/authenticationservices/asauthorizationopenidrequest/nonce).
**Apple's nonce claim** (Apple docs): present only if `nonce` was set on the
ASAuthorizationAppleIDRequest, carrying that string as given. The app will set
`request.nonce` to the relay's nonce, unhashed. Unproven until a real device
ceremony.

**Next units:**
1. **D-132 — iOS sign-in buttons.** Sign in with Apple (`SignInWithAppleButton`,
   no scopes, entitlement `com.apple.developer.applesignin`). Google flow
   (ASWebAuthenticationSession, PKCE S256, state, relay nonce), with the Google
   button HIDDEN until (a) the iOS client id exists and (b) the founder allows
   downloading Google's official G logo.
2. **D-133 — Android `play`: Sign in with Google** via Credential Manager.
3. The roadmap resumes: groups and people, appearance toggle, account lifecycle, …

**Waiting on the founder (asked 2026-09-14, unanswered when D-131 started):**
- The relay deploy. Tell him to deploy D-131's commit instead of `04efa47`; it
  supersedes it.
- Google Cloud OAuth clients: Web, iOS, and Android × 3 SHA-1s.
- The Sign in with Apple capability on the App ID.
- Permission to download `signin-assets.zip` (855,303 bytes) for the G logo.

**Lessons carried from D-130:**
- A sabotage runner's compile check must look for `error[E…]` or "could not
  compile", never `^error:`, which matches cargo's "error: test failed".
- Anchor edits on whole lines: `no client.` matched partway through a longer
  line.

---

## Founder decisions (dated, newest last)

- 2026-08-31 — Device key sharing: approve on an existing device (D-123).
- 2026-09-07 — Ox Alpha retired; Claude builds directly.
- 2026-09-07 — Account surface entry point: account glyph in the Balance header,
  not a fifth tab (D-126).
- 2026-09-13 — GitHub Actions minutes exhausted, $5 budget: CI only when
  necessary; commit with `[skip ci]`; verify locally and on the simulator.
- 2026-09-14 — **Group members: "Add by name, link later."** Type a name and the
  person is in (works for friends who never install SKIPTI). If that person
  installs later, an invite links the name to their device, reusing D-123's
  approval and group-key wrapping. Invites are a follow-up unit.
- 2026-09-14 — **Appearance: a clean toggle between Light, Dark, Night (pitch-
  black AMOLED) and System.** He also says the overall UI looks dated — to be
  fixed later.
- 2026-09-14 — Write a detailed `CLAUDE.md` and keep a detailed build/work log
  after every finished step so work survives compaction.
- 2026-09-14 — **Google Sign-In on both iOS and Android**; change whatever has
  to change to allow it → GUARANTEES #6 amended (ADR-0035, D-129). My calls
  within it, reported to him: no Google SDK on iOS (system sheet + relay code
  exchange); Android `play` flavour only (no Google libraries in `direct`);
  `openid` scope only; relay-issued single-use nonce; no linking by email.
- 2026-09-14 — Founder reports **Play organisation verification and Apple
  developer verification are both done.**
- 2026-09-14 — **Groups (D-134…D-136), founder's answers:**
  1. **Many groups, with a group list** (create: name + settlement currency;
     switch between them).
  2. **Ask your name once, on this device.** You see yourself as **"Tim (You)"**;
     everyone the group is shared with sees **"Tim"**. The "(You)" suffix also
     distinguishes you from a member with the same name.
  3. Old test data: "Do what's best. **People who install it shouldn't have
     anything we are using in test.**" My call, stated to him: new installs start
     with NO groups (no seeding, no fixture people). His simulator ledger is
     kept, not deleted (never auto-delete), and migrated: the fixture people
     become ordinary renamable members and the fixture "Ada" becomes the
     device's self.

## Open questions for the founder (all raised in the 2026-09-14 status report unless noted)

- Sign-in button weight differs: Android account hub uses a filled accent
  Button, iOS uses the hairline secondary style (GUARANTEES #5). Which wins?
  (Raised in the D-128 report, unanswered.)
- Lucide `receipt` contains a dollar-like stroke; the app is used mostly in INR.
  Keep or replace? (Raised in D-128 report, unanswered.)
- Account deletion: SPEC §4.4 requires a **7-day grace period with a cancel
  link** and an export offered first; the relay hard-deletes immediately.
  Implementing grace is a relay change and a deploy (founder's box). Needs a
  decision when the account-lifecycle unit starts.
- UI refresh direction ("looks dated") — needs his input before a design unit.
- Real exchange-rate source (the fetch has been a stub since D-52). **It must be
  GUARANTEES #6-safe**: the app may not call a third-party rate API, so rates
  should come through the relay as signed sets (iOS `FxRates` already verifies a
  pinned signing key). The open decision is where the relay gets them.
- ~~**GUARANTEES #6 vs Google Sign-In**~~ **RESOLVED 2026-09-14: Google on both
  platforms, #6 amended (ADR-0035).** Original note: #6 says the app talks to
  the sync backend and nothing else. An embedded Google Sign-In SDK on iOS
  contacts Google directly, yet ADR-0007 and SPEC §4.2 list Google Sign-In.
  Decide before the Google unit: drop it, limit it to OS-mediated Android
  Credential Manager, or amend #6 through ADR-0013's fork process.

---

## D-129 — groups and people: findings so far

Decision: **add by name, link later** (above).

**Core today**
- One group function: `ffi_create_group(name, settlement_currency, members: Vec<String>)
  -> GroupWire { id, name, settlement_currency, members }` (`core/src/ffi.rs:1323`,
  implementation `create_group` at ~`ffi.rs:499`). Validates: name non-empty after
  trim; currency parses; members non-empty; each member parses as a participant
  id; duplicates refused (`CoreError::DuplicateParticipant`). Group id = 16 random
  bytes from core, hex.
- **No add / remove / rename member function. No member names anywhere in core.**
- `ParticipantId = [u8; 16]` (`core/src/hlutr/mod.rs:4`);
  `parse_participant` requires exactly 32 hex chars (`ffi.rs:291`).
- **No participant-id generator is exported.** Pattern to copy:
  `ffi_rule_new_id()` (`ffi.rs:1768`). New member ids should be minted in core,
  like group ids.
- Import already has a name↔id concept: `flutningr::plan::MemberBinding
  { name, participant_id }`.

**Shells today — everything about people is fixtures**
- Fixture ids: `ada/rahul/priya/amit` = `"a"×31 + "1".."4"`. iOS defines them in
  `ios/Skipti/App/BalanceScreen.swift:8-14` (`Fixtures`, including `names`);
  Android in `BalanceFixtures.kt:23-33` (`names`, `GROUP_NAME = null`,
  `MEMBER_COUNT = 4`).
- **"You" is hardcoded as `Fixtures.ada` / `BalanceFixtures.ada`**, and the payer
  is always ada.
- iOS production uses of fixtures: `LedgerSync.swift:585`; `BalanceScreen.swift:178,179,208,209,234,295`;
  `AddExpenseScreen.swift:46,63-66,302,335`; `SettleScreen.swift:33,34,261,283,288,299`;
  `LedgerStore.swift:184` (seed); `ExpenseListView.swift:14,16`.
- Android production uses: `ExpenseListScreen.kt:184,187`;
  `BalanceScreen.kt:132,180,191,193,195,201,224,244,249,352,353`;
  `LedgerStore.kt:611` (seed); `AddExpenseScreen.kt:218,248,286,304-307`;
  `SettleScreen.kt:137,159,176,277,304,305,334,346,423,430`.
- Groups are seeded on first run by `loadGroupsOrSeed()` (iOS `LedgerStore.swift:174`,
  Android `LedgerStore.kt:599`) as one group "Goa Trip", INR, fixture members.
  `GroupDTO { id, name, settlementCurrency, members: [String] }`, stored as JSON at
  kv key `ledger/groups`. Android also has non-seeding `loadGroups()` (`LedgerStore.kt:631`).
- **Screens only ever use the first group** (`groups.first` / `firstOrNull`).
- **Group data does not sync**: no shell writes `GroupOperation` records;
  groups are per-device kv. (D-121 recorded "groups sync per-device as kv
  fixtures".) So a second device of the same account would not see group names
  or members.
- Name render sites: iOS `BalanceLineView.swift:97,107` (via a `names` map),
  `ExpenseListView.swift:16`, `SettleScreen.swift:34-36`, `AddExpenseScreen.swift:63-66`;
  Android `ExpenseListScreen.kt:187`, `BalanceScreen.kt:352`, `AddExpenseScreen.kt:304-307`,
  `SettleScreen.kt:277,334,346,423,430`.

**SPEC context**
- §4.1: "fully usable with no account at all. Local ledger, local groups via
  QR-code invite over a local network, full export."
- §3 group key distribution: GroupKey wrapped per member device via X25519;
  invite = the invitee's device publishes a public key. §2.5: removal must rotate
  the key. SPEC says nothing about name-only members — the founder's decision
  fills that gap.

**D-134 started 2026-09-14 (this scoping, renumbered from D-129). Re-read facts:**
- `GroupWire { id, name, settlement_currency, members: Vec<String> }` (ffi.rs:482).
  Members are only 32-hex participant ids: no names, and no "self" marker.
  `create_group` (ffi.rs:499) mints the group id as 16 random bytes.
- `merki::new_id()` (merki/mod.rs:686) = hex of `lykill::random_bytes(16)`: the
  exact shape a participant-id minter should copy (ParticipantId = [u8; 16]).
- The only core group test is `groups_scope_the_balance_line_and_creation_validates` (ffi.rs:~2400).
- iOS `LedgerStore.GroupDTO` (LedgerStore.swift:51) and Android `GroupDTO`
  (LedgerStore.kt:76) are identical `{id, name, settlementCurrency, members}`,
  stored as JSON at kv `ledger/groups`. `loadGroupsOrSeed` seeds "Goa Trip"/INR
  with fixture ids. Android also has non-seeding `loadGroups()`, used by the
  read-only home.
- SPEC §5.3 read: Group = "a shared ledger with members and one settlement
  currency". It is SILENT on member names, a self/"you" member, and how many
  groups a person has, so those are product forks. §5.4 lists Payer-only among
  split types (the payer is a member).
- **Asked the founder (2026-09-14), three questions with recommendations:**
  1. Many groups with a list (recommended) vs one group for now.
  2. Ask "your name" once, on this device (recommended) vs always just "You".
  3. The old "Goa Trip" simulator data: keep it and make its people editable,
     with Ada → You and new installs unseeded (recommended), vs start clean
     (hidden, nothing deleted).
  **ANSWERED** (see Founder decisions). Plan:
  - D-134 core: mint participant ids; members with names; add/rename member.
  - D-135 iOS: group list, create flow asking your name first time, add
    people, payer picker, fixtures and seeding removed, migration.
  - D-136 Android twin.
  **D-134 core design (2026-09-14, being written):**
  - `MemberWire { id, name }` and `NamedGroupWire { group: GroupWire, self_id,
    members: Vec<MemberWire> }`. Invariant: `members` and `group.members` hold
    the same ids in the same order, and `self_id` is one of them.
  - `new_participant_id()`: hex of `lykill::random_bytes(16)`, like group ids
    and `merki::new_id`.
  - `member_name(raw)`: trim; refuse empty; refuse > 64 CHARACTERS (not bytes;
    64 matches the relay's label clamp); refuse control characters. Refused,
    never silently cut.
  - Duplicate names: two NON-self members may not share a name (Unicode
    lowercase compare, same idea as merki's sibling rule). Self is exempt, so
    "Tim (You)" and another "Tim" can coexist (founder's example).
  - `create_named_group(name, currency, self_member, other_names)`: self first,
    others' ids minted, then `create_group` validates name, currency and dedup.
  - `add_member`, `rename_member` (unknown id → `UnknownParticipant`), and
    `named_group(group, self_id, members)`, which validates a group loaded
    from storage (for the shells' migration).
  - Errors reuse `CoreError::InvalidInput` / `UnknownParticipant`. No new
    `CoreError` variant, so no shell `when`/`switch` exhaustiveness breaks.
  - OUT OF SCOPE (residue): removing a member (touches existing balances and,
    later, key rotation, §2.5).
  - My calls, to report: "(You)" is composed in the shells (display copy); the
    64-char cap; the refusal of duplicate other-member names.
  **D-134 code WRITTEN (2026-09-14)** in `core/src/ffi.rs`, all asserted-anchor
  edits:
  - After `create_group`: `MemberWire`, `NamedGroupWire`, `MEMBER_NAME_MAX_CHARS
    = 64`, `new_participant_id`, `member_name`, `other_member_named`,
    `duplicate_name`, `named_group`, `create_named_group`, `add_member`,
    `rename_member`.
  - After `ffi_create_group`: six exports (`ffi_new_participant_id`,
    `ffi_member_name`, `ffi_named_group`, `ffi_create_named_group`,
    `ffi_add_member`, `ffi_rename_member`).
  - After the groups test: helpers `me()` and `names()`, plus 5 tests:
    `participant_ids_are_minted_in_core_and_parse`,
    `member_names_are_trimmed_bounded_by_characters_and_printable`,
    `a_named_group_puts_you_first_and_mints_everyone_elses_id`,
    `adding_and_renaming_keep_the_names_and_the_ids_in_step`,
    `a_stored_group_whose_names_and_ids_disagree_is_refused`.
  - **GREEN:** fmt ok. The 5 new tests plus the existing groups test: 6 passed.
    Full core lib: **173 passed, 0 failed**.
  - **clippy `-D warnings` FAILED on style in my new test:** four errors on two
    lines, `std::iter::repeat('ह').take(N).collect()` →
    `clippy::manual_str_repeat` and `clippy::manual_repeat_n`. Fixed as
    `"ह".repeat(N)` (asserted edit, no logic change). **Re-run: clippy clean,
    fmt ok, core lib 173 passed.**
  - **`make bindings` rc=0.** Regenerated `ios/Skipti/Bindings/skipti_core.swift`,
    `skipti_coreFFI.h` and `android/.../uniffi/skipti_core/skipti_core.kt`.
    Verified:
    - Kotlin: all 6 `fun ffi…` plus `data class MemberWire` and `NamedGroupWire`.
    - Swift: all 6 `public func ffi…` plus `public struct MemberWire` and
      `NamedGroupWire`, with declaration lines found (e.g. `ffiCreateNamedGroup`
      at 6337).
    - Header: 6 `uniffi_skipti_core_fn_func_ffi_*` symbols.
    **My first Swift count said 0:** `grep -c pattern file` over a glob that
    matches a single file prints no `file:` prefix, so `awk -F: '{s+=$2}'` summed
    nothing. It was recounted directly.
  - **Core sabotage RUNNING** (background), results → `d134/results_core.json`.
    - **C1 OK** (cap counts bytes) → exactly
      `member_names_are_trimmed_bounded_by_characters_and_printable`; no
      compile error; `ffi.rs` restored byte-identical.
    - **C2 OK** (control characters allowed) → exactly the same test; restored.
    - **C3 OK** (you lose the same-name exemption) → exactly
      `a_named_group_puts_you_first_and_mints_everyone_elses_id` and
      `adding_and_renaming_keep_the_names_and_the_ids_in_step`; restored.
    - **C4 OK** → create + stored + edit. **C5 OK** → stored. **C6 OK** → edit.
      **C7 OK** → edit.
    - **SABOTAGE 7/7 OK** (`CORE DONE 7 of 7 OK`). Tree verified restored: no
      sabotage string in `ffi.rs`, and both guarded lines are present once.
    - **Regenerated bindings STAGED** (`git add` of skipti_core.swift,
      skipti_coreFFI.h, skipti_core.kt), then **full gate STARTED** (background),
      log in the scratchpad at `d134/gate.log`. NO tracked edits until it finishes.
    - **GATE PASSED 17/17, 0 skipped** (2026-09-15T02:35:36Z; bindings staged, so
      the freshness leg compared what is being committed). cargo test 327; iOS
      124/8 skipped/0 failures; JVM 250 = 125 × 2, BOTH variants run in this
      gate (22:35:30 / 22:35:36).
    - **BUILD-LOG RECORDED** (positive dry run rc=0; D-01..D-134 contiguous; no
      placeholder leaked). Committing; the pre-commit fast gate runs because
      ffi.rs is staged.
    - The D-134 record drafts are in the scratchpad at
      `d134/final/`; only SABOTAGE SUMMARY/ROWS remain besides the auto-fills.
  - **Bindings freshness mechanics** (gate.sh:181–203, FULL gate only; the
    pre-commit fast gate does not run it): rebuild the release dylib, `make
    bindings`, then `git diff --exit-code -- ios/Skipti/Bindings
    android/app/src/main/kotlin`. Plain `git diff` compares the WORKING TREE
    against the INDEX. So D-134's plan: **stage the regenerated bindings before
    the full gate.** Regeneration then reproduces the staged bytes and the leg
    passes, which is the check's real meaning ("generated == what will be
    committed"). The record will say so, so no one reads it as evasion.
  - `d134/record_d134.py` is derived from record_d133 with the D-133 anchors
    (row, residue `(f)` line, changelog last line), all verified unique. My sed
    left an unescaped `"android"` inside a double-quoted Python string; fixed
    with single quotes, and it parses.
  - **Sabotage runner** in the scratchpad at `d134/sabotage_core.py`: 7 rows,
    expected sets fixed up front, full core lib suite per row.
    - C1 bytes cap → names.
    - C2 control allowed → names.
    - C3 self exemption lost → create + edit.
    - C4 case-sensitive duplicates → create + edit + stored.
    - C5 count-not-order → stored.
    - C6 rename skips the duplicate check → edit.
    - C7 add_member skips `group.members` → edit.

**Design questions still to settle inside D-129 (mine unless they turn out to be forks)**
- Where names live: in `GroupDTO` (kv) now, with group records for sync as a
  named follow-up — vs. writing group records in this unit.
- A real per-ledger "self" participant id replacing `ada`, created once and
  persisted; what existing installs (the founder's simulator ledger) migrate to.
- Payer selection on Add.
- Whether this unit adds multiple groups (a group switcher) or one editable
  group; new installs should not be seeded with "Goa Trip" fixtures.

---

## Account lifecycle findings (for the unit after D-129)

- **Sign-out is unsafe to expose as-is.** `LedgerSync.signOut()` on iOS
  (`LedgerSync.swift:151`) and Android (`LedgerSync.kt:359`) only clears the
  session key and state. It leaves the sync secret, the device agreement keypair,
  the authority marker (`sync/key-authority`), and the cursors. Signing into a
  *different* account afterwards would seal that account's records with the old
  account's key. Nothing in the UI calls `signOut()` today.
- **Account deletion**: iOS has `IdentityService.deleteAccount(accessToken:)`
  (`ios/Skipti/Relay/IdentityService.swift:~213`) with no UI caller. **Android has
  no delete call at all** (`IdentityService.kt` has only requestOTP, verifyOTP,
  refresh). The relay's `account_delete` hard-deletes immediately: account,
  blobs, envelopes, devices, memberships, escrow, passkeys, OTP state.
- **SPEC §4.4 requires**: reachable in two taps; no dark patterns; **7-day grace
  with a cancel link**; **export offered before deletion in the same flow**;
  last-member groups destroyed, others survive with the member removed and keys
  rotated; verified by a test that queries the store.
- Store requirement: both Apple and Google require in-app deletion; Google also
  wants a web URL (SPEC §12.1).

---

## Appearance toggle — Light / Dark / Night (AMOLED) / System

- Today both shells **follow the system setting** (proven on the simulator
  2026-09-14: dark appearance renders ADR-0023's dark palette). There is no
  in-app switch and no Night palette.
- **iOS**: deployment target 17.0, so a custom `UITraitDefinition` can carry
  "night" alongside `userInterfaceStyle`. `Tokens.dynamic(light:dark:)` in
  `DesignTokens.swift` resolves on `userInterfaceStyle`; it needs a third value.
  The preference is a non-sensitive UI setting needed before the vault opens,
  so UserDefaults is the natural store.
- **Android**: `enum class Tok(lightC, darkC)` with `invoke(dark: Boolean)`;
  141 `Tok.*(…)` calls and 123 `Type.*(dark)` calls; `isSystemInDarkTheme()`
  in 19 places. Night needs a palette value instead of a Boolean — a wide but
  mechanical refactor. Preference store: SharedPreferences/DataStore.
- **Night palette must be contrast-verified before adoption**, the way ADR-0023
  §4 and §8 did (WCAG body 4.5:1, UI 3:1, per token pair actually used), and
  recorded as an ADR-0023 amendment. Starting point: `bg #000000`; surfaces must
  stay distinguishable from pure black (hairline-led structure); text and money
  colours likely carry over from dark but must be re-measured on #000.
- Where the toggle lives: the Account hub / settings surface (SPEC references a
  Settings surface; the device list must not be nested inside it).

---

## Roadmap to publish (as assessed 2026-09-14)

Estimate in **units** (one D-numbered piece of work). Pace in the D-124–D-128
stretch was roughly 2 units per long working session.

**Must-have for a public v1 on both stores**
| Work | Units | Notes |
|---|---|---|
| Groups and people (D-129) | 2–3 | Create a group, add people by name, pick payer, a real "You"; replaces fixtures |
| Edit and delete expenses and settlements | 1–2 | Records are immutable: needs superseding/tombstone records |
| Account lifecycle | 2 | Safe sign-out, in-app deletion both shells (+Android client), §4.4 grace + export-first (relay change → founder deploy) |
| Sign in with Apple on iOS | 1 | Relay route exists (D-63); ships with Google on iOS (D-131) |
| Passkey sign-in on iOS | 1 | Android already offers it |
| Google Sign-In | 3 | D-130 relay, D-131 iOS (+Apple), D-132 Android `play`; #6 amended by ADR-0035 (decided 2026-09-14) |
| "SETTLED IN" currency label bug (both shells) | ≤1 | Fold into D-129 |
| Real FX rate source | 1–2 | Stubbed since D-52; must be GUARANTEES #6-safe (relay-served signed sets); source decision needed |
| Appearance toggle + Night palette | 1–2 | Both shells; ADR-0023 amendment |
| Compliance engineering | 2 | PrivacyInfo.xcprivacy, ITSAppUsesNonExemptEncryption, privacy policy doc, deletion web URL, backup exclusion (the WrongKey hazard) |
| Android parity for D-127/D-128 findings | 1 | e.g. the `try?`-shaped vault error on launch, disabled-button dimming |
| Release builds | 1 | Signed AAB + TestFlight archive — with the founder |
| Real-device verification | 1–2 | Passkey ceremonies, VoiceOver/TalkBack, real-phone smoke — founder's phones |
| UI refresh ("looks dated") | 3–4 | Needs founder direction |

**Total ≈ 19–27 units → roughly 9–13 more long working sessions to a public
launch.** A **tester build** (TestFlight + Play internal testing, usable with
real groups, edit/delete, safe sign-out/deletion, Apple sign-in, compliance
minimum, release builds) is about 10–14 units → **roughly 5–7 sessions**.

**Not included**: the web PWA (genuinely zero; 6–10 units on its own), group
invites/linking (the "link later" half), device revocation and key rotation,
key-change detection.

**Gated on the founder, not on code**: his iPhone and Android phone for device
ceremonies; Apple/Play console setup (Play **organisation verification** and
Apple developer verification: **DONE**, founder 2026-09-14); Google Cloud OAuth
clients; hosting
the privacy policy; Data safety form, privacy nutrition labels, financial
features declaration, store screenshots; relay deploys.

**Honest note**: the 2026-08-29 handoff estimate (~5 pastes to TestFlight,
12–18 to v1 including web) was made before anyone had checked that users
cannot create groups, add people, edit or delete expenses, or sign out/delete
their account in the UI. It was optimistic.

---

## Tooling gaps found (not yet fixed)

- ~~`.githooks/pre-commit`'s `GATE_RELEVANT` pattern does not include the check
  scripts~~ **Fixed in D-137:** the hook now runs the cheap legs (which include
  both check scripts) on every commit; proven by the self-test's
  `test_check_script_only_commit_runs_the_cheap_legs` and sabotage row S10.

## Google sign-in design (ADR-0035; relay D-130 + D-131, iOS D-132, Android D-133)

**Relay (D-130)** — `server/src/google.rs`, the `apple.rs` pattern:
- `POST /v1/auth/google/start` → `{ nonce }`: 32 random bytes hex, single use,
  TTL 10 min, bounded in-memory map (D-103's flood lesson: cap the map; a flood
  cannot evict live entries). Floored route (`floored_auth` in `router`).
- `POST /v1/auth/google` with `{ id_token, nonce, device_label?, sync_device_id? }`
  (Android Credential Manager returns an ID token directly).
- `POST /v1/auth/google/code` with `{ code, code_verifier, nonce, device_label?,
  sync_device_id? }` (iOS): relay POSTs to `https://oauth2.googleapis.com/token`
  with the **iOS** client id, `grant_type=authorization_code`, the fixed redirect
  URI `com.googleusercontent.apps.<ios-id>:/oauth2redirect`, then verifies the
  returned `id_token` exactly like the Android path. **Unproven until real client
  ids exist: that Google accepts a server-side exchange for an iOS-type client
  (public client, no secret). If not, fallback = the app exchanges, and ADR-0035
  constraint 3 must be amended — do not quietly switch.**
- Verification: RS256 only; JWKS `https://www.googleapis.com/oauth2/v3/certs`
  (cache 1 h, refetch on unknown kid); issuer `https://accounts.google.com` OR
  `accounts.google.com`; audience ∈ {web client id (Android `serverClientId`),
  iOS client id}; exp/iat required with 60 s leeway; `nonce` claim must equal a
  live relay-issued nonce, consumed on use (consumed even on failure? decide in
  D-130 — consuming on any attempt is the safer default); only `sub` on the
  claims struct. Identifier `google:{sub}` → `bind_identity` → same session tail
  as `auth_apple`. Every failure = `passkey_reject()` (uniform body, D-27).
- Config: audiences from env (`SKIPTI_GOOGLE_WEB_CLIENT_ID`,
  `SKIPTI_GOOGLE_IOS_CLIENT_ID`); with neither set the Google routes reject
  everything (never accept an empty audience). Client ids are public, not
  secrets.
- Tests: copy `server/tests/apple_http.rs` (pinned RSA test key, local JWKS
  server): happy path both audiences, wrong iss/aud/alg/exp, missing/reused/
  foreign nonce, unknown kid refetch, uniform rejection body, `email` claim
  present but never stored, token-endpoint failure for the code path (local
  fake token endpoint — make the token URL injectable like the JWKS URL).
- **Apple path has no nonce today** (D-63). Recorded as a gap, not changed in
  D-130.

**iOS (D-131)** — no new dependency. `ASWebAuthenticationSession` with
callback scheme = reversed iOS client id; auth URL
`https://accounts.google.com/o/oauth2/v2/auth` with `client_id`, `redirect_uri`,
`response_type=code`, `scope=openid`, `code_challenge` (S256), `state`,
`nonce`. PKCE verifier + state random — consider a core export so both shells
share a golden-tested generator (RFC 7636 Appendix B vector). Sign in with
Apple: `ASAuthorizationController` → `/v1/auth/apple`; needs the
`com.apple.developer.applesignin` entitlement (`Default`) in
`ios/Skipti/Skipti.entitlements` AND the capability on the App ID (founder).
Buttons follow each provider's branding rules; GUARANTEES #5: secondary
weight, never nudging.

**Android (D-132)** — `playImplementation` only:
`com.google.android.libraries.identity.googleid:googleid:1.2.0` and
`androidx.credentials:credentials-play-services-auth` (align with
`androidx.credentials:credentials`, currently 1.5.0; 1.6.0 is the latest
stable as of 2026-09-14). `GetSignInWithGoogleOption(serverClientId = web
client id).setNonce(nonce)` → `GoogleIdTokenCredential.idToken` →
`/v1/auth/google`. Code in `src/play/java`; `src/direct` gets a no-op
provider so shared UI shows no Google button. Rule-4 justification in the
Gradle comment, like D-118's.

**Research against Google's docs (2026-09-14) — facts that shape D-130…D-132:**
- iOS custom-scheme redirect: developers.google.com/identity/protocols/oauth2/native-app
  says "Custom URI schemes are no longer supported on Android and Chrome apps" in
  its redirect table (which still lists `com.googleusercontent.apps.123:/oauth2redirect`),
  but its "App redirect methods" section also says, with no platform named,
  "Custom URI schemes are no longer supported due to the risk of app
  impersonation", and points iOS to the Google Sign-In iOS SDK or AppAuth.
  Google's own iOS SDK uses the reversed-client-id scheme, so it very likely
  still works for iOS clients — but it is UNPROVEN until real client ids exist.
  **Fallback if Google refuses:** GoogleSignIn-iOS (a dependency, rule 4) or
  AppAuth — either changes ADR-0035's "no new iOS dependency", so it goes back
  to the founder rather than being switched quietly.
- Token exchange: "The client_secret is not applicable to requests from
  clients registered as Android, iOS, or Chrome applications" — the relay's
  secret-less exchange as the iOS client matches the documented shape.
- Android (developer.android.com/identity/sign-in/credential-manager-siwg-implementation):
  `setServerClientId` takes the **Web** client id; nonce via `setNonce`, and
  "server-side code validates that the request and response nonces are
  identical" (D-130 does). An Android OAuth client (package + SHA-1) must exist
  per signing certificate (debug, upload, Play App Signing) or Credential
  Manager fails with 28444 "Developer console is not set up correctly".
  The doc's snippet pins credentials 1.7.0-alpha03; D-132 uses the latest
  STABLE (credentials 1.6.0, googleid 1.2.0) unless a needed API is alpha-only.
- Google Auth Platform: Branding → Audience → Data Access → Clients. openid-only
  apps get "Basic app verification"/brand verification. A **production** app
  needs a public home page with links to a privacy policy (and terms); until
  then the app stays in Testing, where only listed test users can sign in —
  enough for TestFlight / Play internal testing. The privacy policy is
  therefore on Google sign-in's critical path too.

**Founder console steps (sent 2026-09-14)**: Google Cloud project → Google Auth
Platform: Branding, Audience = External → Production, Data access = `openid`
only; Clients: Web application ("SKIPTI relay", no redirect URIs), iOS (bundle
`com.vardrlabs.skipti`, team `27GK2T7UCT`), Android `com.vardrlabs.skipti` × SHA-1
of (a) debug keystore `EA:43:A9:4E:34:14:26:F0:08:3D:4E:75:AD:08:40:0A:38:2D:F6:1A`,
(b) upload key, (c) Play App Signing key. Apple: enable Sign in with Apple on the
`com.vardrlabs.skipti` App ID. He sends back the Web and iOS client ids.

## D-132 design — iOS sign-in buttons (drafted 2026-09-14 while D-131's sabotage ran)

**Apple button: UIKit's `ASAuthorizationAppleIDButton` in a UIViewRepresentable,
not SwiftUI's `SignInWithAppleButton`.** SwiftUI's `onRequest` configures the
request synchronously, but the nonce comes from the relay (`startAppleSignIn()`,
async). The alternatives both break a rule: fetching a nonce when the screen
appears spends relay nonces nobody tapped for, and hashing a client-made nonce
defeats "a nonce the relay issued". The UIKit button IS Apple's button (HIG),
and its target action runs: fetch nonce → `ASAuthorizationAppleIDProvider().createRequest()`
with `requestedScopes = []` (no name, no email — ADR-0035 constraint 4) and
`nonce = relayNonce` (unhashed) → reuse `PasskeyCeremony.run(requests:)` →
`ASAuthorizationAppleIDCredential.identityToken` (UTF-8) → `signInWithApple`.
Button style follows the colour scheme: `.black` in light, `.white` in dark.
Entitlement `com.apple.developer.applesignin = [Default]` in `Skipti.entitlements`.
A user cancel (`ASAuthorizationError.canceled`) shows no error text.

**Google flow (`GoogleSignIn.swift`, no dependency):**
- `GoogleSignInConfig.iosClientID: String?` — nil until the founder sends it. The
  button is HIDDEN while nil or while the logo permission is unanswered.
- PKCE: verifier = base64url(32 random bytes) = 43 chars; challenge =
  base64url(SHA256(verifier)) via CryptoKit. Golden: RFC 7636 Appendix B —
  verifier `dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk` → challenge
  `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`.
- State: base64url(16 random bytes). The callback must echo it exactly.
- Auth URL `https://accounts.google.com/o/oauth2/v2/auth`: client_id,
  redirect_uri = `com.googleusercontent.apps.<prefix>:/oauth2redirect` (same
  derivation as the relay's `ios_redirect_uri`; test both against one
  hand-written value), response_type=code, scope=openid, code_challenge,
  code_challenge_method=S256, state, nonce (relay's).
- `ASWebAuthenticationSession(url:callbackURLScheme:completionHandler:)`
  (iOS 12+ init; the `.customScheme` callback API is 17.4+ and the target is
  17.0). **My call: `prefersEphemeralWebBrowserSession = true`** — no Safari
  cookies shared, so the flow leaves nothing in Safari's jar. The cost is that
  the user types their Google credentials each time; sign-in is rare. Report it.
- Callback parse: `error=` → cancelled/refused (no oracle text); state mismatch
  → refuse; missing code → refuse. Then `IdentityService.signInWithGoogleCode(code:codeVerifier:nonce:…)`
  → `/v1/auth/google/code`; `startGoogleSignIn()` → `/v1/auth/google/start`.

**Screen:** SignInScreen gets a provider section above the email-code form:
Apple button (and Google, when enabled) at equal weight, then a one-line
statement per SPEC §4.2 item 1 / §11.6 register, e.g. "Apple or Google only
confirm it is you. They never see your ledger, and a new device still needs
one of yours to approve it." — run the §14.6 filter before shipping. Sign-in
is reached only by tapping "Sign in" (GUARANTEES #5 unaffected).

**pbxproj anchors (read 2026-09-14; re-assert before use):**
- App file shape: `A1xxxx /* X.swift in Sources */ = {isa = PBXBuildFile; fileRef = B1yyyy /* X.swift */; };`
  and `B1yyyy /* X.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; name = X.swift; path = Skipti/Relay/X.swift; …}`.
  Group child next to `B10025 /* IdentityService.swift */,` (group `D10002 /* App */`, ~line 216).
  App Sources phase entry next to `A10023 /* IdentityService.swift in Sources */,` (~line 426).
- Test file shape: `B1yyyy /* T.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = T.swift; sourceTree = "<group>"; };`.
  Group child next to `B10027 /* RelayClientTests.swift */,` (~line 253).
  Test Sources phase entry next to `A10025 /* RelayClientTests.swift in Sources */,` (~line 461).
- Free as of this read: A10043, A10044, A10045, B10112, B10113, B10114 (0 occurrences).
- PKCE golden re-derived independently in Python (hashlib/base64): RFC 7636
  App. B verifier → `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM` ✓, and the
  verifier itself from App. B's 32 octets ✓.
**Tests:** PKCE golden; URL query items exact; redirect derivation; callback
parser (success, state mismatch, error=access_denied, missing code); transport
bodies for google start/code; Apple nonce flows through to the body. Sabotage
each. Visual check on the simulator: Apple button renders in light and dark;
Google button absent.

## Simulator and device state

- iPhone 17 simulator `EB725812-1E80-44DA-BE0D-E429F90648E3`, appearance
  **light** (switched to dark briefly on 2026-09-14 to prove dark mode, then
  restored).
- Onboarding completed on the current install. The ledger holds the seeded
  "Goa Trip" data **plus a test expense "D-128 check", $10 USD split You/Rahul**,
  added to prove Add's save → Balance.
- Last installed build: D-128 tree (`b077e95`).

---

## Session log (append-only, newest last)

### 2026-09-07 → 2026-09-14
- D-124 safety phrase (A-01) · D-125 sync default-on · D-126 account surface ·
  D-127 iOS first run (WrongKey brick, VoiceOver escapes, date bug, CTA pass) ·
  D-128 iOS product shell (HomeShell, Lucide parser, unstyled buttons, AccentColor,
  autolink root cause, tally corrections). All recorded in BUILD-LOG.
- 2026-09-14: founder asked for status + estimate; the scoping reads for D-129
  and account lifecycle were done (above). Decision: add by name, link later.
- 2026-09-14: founder asked for `CLAUDE.md`, a per-step work log, and a
  Light/Dark/Night/System appearance toggle. `CLAUDE.md` and this file written,
  committed as `b596f41`, pushed with `[skip ci]` (no Actions run). Status report
  and estimate sent.
- 2026-09-14: the founder asked me to double-check both files. Verified against
  the tree and corrected in `CLAUDE.md`: the pbxproj ID note (A-IDs are mixed
  across targets; A10043 and B10112 free); the gate-leg counting explanation;
  all seven guarantees, with #6's consequences for Google Sign-In and exchange
  rates; a new §7.2 of commands (Makefile targets, single tests, iOS simulator,
  Android emulator — recovered from BUILD-LOG); the §5 incident-list format;
  the pre-commit blind spot. In this file: the stale NOW block, the open
  questions, #6-aware roadmap rows, the tooling-gaps section.
  Further checks corrected three more `CLAUDE.md` claims: `ANDROID_HOME` is unset
  and `adb`/`emulator` are not on `PATH` (full SDK paths now given); uiautomator
  dumps omit Compose error-state text (D-110), so screenshots are the oracle;
  `make ios-app`'s second step is an unsigned Release build for generic iOS.
  Confirmed true: `c4c5815` restored the D-55 row; ADR-0007 lists Google Sign-In;
  A10043/B10112 unused; the hook pattern excludes the check scripts.
  **A mistake of mine, corrected in chat:** I told the founder incident 24 was
  never written into BUILD-LOG. It was — §5 item 24. My search for "§5.24" only
  matched references to it, because §5 is a plain numbered list.
