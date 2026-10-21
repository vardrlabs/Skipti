# C2 Usernames Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every account picks a username right after sign-in (with three suggestions and a live availability check), may change it exactly once, and can never take a blocked or reserved one.

**Architecture:** The cloud decides (`firebase/functions/people.js` pure rules, `index.js` callables): one JSON blocklist, a pattern rule, a set-once-change-once state on `users/{uid}`, and two new callables (`checkUsername`, `suggestUsernames`). Both phones bundle the same JSON and mirror the rule for an instant answer, then show a required username step after sign-in, a change flow with a confirm in You, and a card for accounts signed in before this shipped.

**Tech Stack:** Firebase Functions (Node 22, plain JS, node:test against the emulators), SwiftUI (iOS 17+), Jetpack Compose, kotlinx.serialization.

**Spec:** `docs/superpowers/specs/2026-10-13-feedback-round-design.md`, section "C2 Usernames: design". Mock-up v2: https://claude.ai/artifact/MBhJTr3LbzmgWg2EaFBxJU (source `.drafts/c2/username-mock.html`).

## Global Constraints

- Username shape: `^[a-z0-9_]{3,20}$`, stored lower case without `@`.
- Required step after sign-in, **no close button**; only when the account has no username.
- Accounts already signed in without a username: a **card** on Home and You, never a screen at app open (GUARANTEES #2).
- Set once free; **one** change; **never removed**; the old handle is **released at once**.
- A refusal never says which word matched: "That username is not available." for reserved and blocked alike.
- Suggestion label copy: **"Suggested for you"** (founder's wording). Three chips.
- Live check: from 3 characters, after a 400 ms pause.
- Confirm copy: "Change to @new?" / "You can change your username once. After this, @new is yours for good, and @old is free for anyone to take." Buttons "Change username" / "Keep @old".
- New daily limit `checks: 100` for both new callables.
- No new dependency. No em dashes in copy. Twins: same copy, order and behaviour on iOS and Android.
- Commits: `[skip ci]`, explicit paths only, no attribution of any kind (CLAUDE.md §12).
- Never edit tracked files while the full gate runs; drafts in `.drafts/c2/`.

## File map

| File | Responsibility |
|---|---|
| `firebase/functions/blocklist/usernames.json` (create) | THE list: `reserved`, `anywhere`, `whole`, `raw` (already drafted at `.drafts/c2/usernames.json`) |
| `firebase/functions/people.js` (modify) | `termPattern`, `blocked`, `username` (now `unavailable`), `usernameStep`, `suggestions`, `checks` limit; `RESERVED` removed |
| `firebase/functions/index.js` (modify) | `setUsername` rules; `checkUsername`, `suggestUsernames` callables |
| `firebase/tests/people.test.mjs` (modify) | pure-rule goldens |
| `firebase/tests/functions.test.mjs` (modify) | emulator tests of the callables |
| `firebase/tests/rules.test.mjs` (modify) | a phone cannot write `username` / `usernameChanged` |
| `ios/Skipti/App/UsernameRules.swift` (create) | the rule mirrored, the bundled list, `UsernameState` |
| `ios/Skipti/App/UsernameStep.swift` (create) | `UsernameCopy`, `UsernameField` (live check + chips), `UsernameStep`, `UsernameChangeSheet`, `UsernameCard` |
| `ios/Skipti/App/People.swift` (modify) | `accountUsername`, `checkUsername`, `suggestUsernames`; `usernameProblem` via the rules; `reserved` and `UsernameSheet` removed; FindMeCard by state |
| `ios/Skipti/App/SignInScreen.swift`, `OverviewScreen.swift`, `AccountHubScreen.swift` (modify) | the step after sign-in; the card on Home and You |
| `ios/Skipti.xcodeproj/project.pbxproj` (modify) | 2 Swift files + the JSON resource |
| `ios/SkiptiTests/UsernameRulesTests.swift` (create) + pbxproj | goldens, list identity, state |
| `android/.../UsernameRules.kt` (create), `UsernameStep.kt` (create) | the twins |
| `android/.../People.kt`, `AppRoot.kt`, `Profile.kt`, `AccountHub*.kt` (modify) | twins of the iOS changes |
| `android/app/build.gradle.kts` (modify) | the JSON as an asset; declared as a JVM test input |
| `android/app/src/test/.../UsernameRulesTest.kt` (create) | the twin tests |

## Goldens (re-derived independently by `.drafts/c2/golden.py`, not by the app code)

Allowed: `classic`, `peacock`, `grape`, `assam`, `method`, `prithvi_saha`, `kshitij`, `nazir`, `therapist`, `badminton`, `pineapple`, `spicy`, `bobsled`, `tunnel`, `dallas`, `brandi`, `sunni_muslim`, `team_rocket`, `me_and_you`, `supportive_sam`, `pakistani`, `hello`, `@Priya_92`.

Refused (unavailable): `scunthorpe` (known residue), `f_u_c_k`, `fuuuck`, `b1tch`, `dick69`, `support`, `supp0rt`, `tech_support`, `skipti_fan`, `x_spic_99`, `n4zi`, `sh1t`, `bullsh1t`, `booooobs`, `help`, `x1488x`, `asss`, `vardr_labs`, `SKIPTI`.

Refused (shape): `me`, `ab`, `pri ya`, `aaaaaaaaaaaaaaaaaaaaa` (21).

Every shell's golden test uses exactly these lists.

---

### Task 1: The list and the rule in the cloud (pure)

**Files:**
- Create: `firebase/functions/blocklist/usernames.json` (copy of `.drafts/c2/usernames.json`)
- Modify: `firebase/functions/people.js:13-32` (USERNAME, LIMITS, RESERVED, `username`) and `charge`
- Test: `firebase/tests/people.test.mjs`

**Interfaces:**
- Produces: `people.termPattern(term) -> string`, `people.blocked(handle) -> boolean`, `people.username(raw) -> {handle} | {error: 'missing'|'shape'|'unavailable'}`, `people.usernameStep(old, next, changed) -> {same}|{first}|{change}|{error}`, `people.suggestions(name, year2) -> string[]`, `LIMITS.checks === 100`.

- [ ] **Step 1: Copy the list into the repo**

```bash
mkdir -p firebase/functions/blocklist && cp .drafts/c2/usernames.json firebase/functions/blocklist/usernames.json
python3 -I .drafts/c2/golden.py firebase/functions/blocklist/usernames.json | grep -c ': '
```
Expected: `46` (every golden case printed).

- [ ] **Step 2: Write the failing tests** (replace the first test in `people.test.mjs` and add the rest after it)

```js
const ALLOWED = ['classic', 'peacock', 'grape', 'assam', 'method', 'prithvi_saha', 'kshitij', 'nazir', 'therapist',
  'badminton', 'pineapple', 'spicy', 'bobsled', 'tunnel', 'dallas', 'brandi', 'sunni_muslim', 'team_rocket',
  'me_and_you', 'supportive_sam', 'pakistani', 'hello', '@Priya_92'];
// scunthorpe is the known false refusal ("cunt" anywhere), kept on purpose.
const REFUSED = ['scunthorpe', 'f_u_c_k', 'fuuuck', 'b1tch', 'dick69', 'support', 'supp0rt', 'tech_support',
  'skipti_fan', 'x_spic_99', 'n4zi', 'sh1t', 'bullsh1t', 'booooobs', 'help', 'x1488x', 'asss', 'vardr_labs', 'SKIPTI'];
const BAD_SHAPE = ['me', 'ab', 'pri ya', 'a'.repeat(21)];

test('a username is 3–20 of a–z, 0–9 and _, stored lower case without @', () => {
  assert.deepEqual(people.username('  @Priya_92 '), { handle: 'priya_92' });
  assert.deepEqual(people.username('abc'), { handle: 'abc' });
  for (const bad of [...BAD_SHAPE, 'priya!', 'príya', 'pri.ya', '', '@']) {
    assert.deepEqual(people.username(bad), { error: 'shape' }, bad);
  }
  assert.deepEqual(people.username(42), { error: 'missing' });
});

// C2: the goldens come from .drafts/c2/golden.py, written from the spec's
// words, not from this code.
test('the blocklist refuses abuse and impersonation, in disguise too, and spares real names', () => {
  for (const ok of ALLOWED) assert.ok(people.username(ok).handle, ok);
  for (const no of REFUSED) assert.deepEqual(people.username(no), { error: 'unavailable' }, no);
});

test('a term matches with its letters repeated, never shrunk', () => {
  assert.equal(people.termPattern('boobs'), 'b+o{2,}b+s+');
  assert.equal(people.termPattern('aai_ghal'), 'a{2,}i+g+h+a+l+');
  assert.equal(people.termPattern('fuck'), 'f+u+c+k+');
});

test('set once, change once, never removed', () => {
  assert.deepEqual(people.usernameStep(null, null, false), { same: true });
  assert.deepEqual(people.usernameStep('al', 'al', true), { same: true });
  assert.deepEqual(people.usernameStep(null, 'al', false), { first: true });
  assert.deepEqual(people.usernameStep('al', 'bo', false), { change: true });
  assert.ok(people.usernameStep('al', 'bo', true).error, 'the one change is used');
  assert.ok(people.usernameStep('al', null, false).error, 'never removed');
});

test('suggestions come from the name, well formed and never blocked, best first', () => {
  assert.deepEqual(people.suggestions('Prithvi Saha', 26),
    ['prithvi', 'prithvisaha', 'prithvi_saha', 'psaha', 'prithvi_s', 'sahaprithvi', 'prithvi26', 'prithvi_26']);
  assert.deepEqual(people.suggestions('  Príya ', 26), ['priya', 'priya26', 'priya_26', 'its_priya']);
  assert.deepEqual(people.suggestions('Al', 26), ['al26', 'al_26', 'its_al']);
  assert.deepEqual(people.suggestions('Support Team', 26), ['supportteam', 'steam', 'teamsupport']);
  assert.deepEqual(people.suggestions('राहुल', 26), []);
  assert.deepEqual(people.suggestions(42, 26), []);
});
```

Also in the existing `'daily limits: 40 searches, 2000 fingerprints, 50 invites; …'` test, add after its last assertion:

```js
  const c = people.charge(null, '2026-10-13', 'checks', 100);
  assert.equal(c.checks, 100);
  assert.equal(people.charge(c, '2026-10-13', 'checks'), null, 'the 101st check of the day is refused');
```

- [ ] **Step 3: Run to see them fail**

Run: `node --test firebase/tests/people.test.mjs`
Expected: FAIL (`people.termPattern is not a function`, `usernameStep`, `suggestions`, `unavailable` vs `reserved`).

- [ ] **Step 4: Implement in `people.js`**

Replace the `LIMITS` line, the `RESERVED` block and `username()` with:

```js
import { readFileSync } from 'node:fs';

export const LIMITS = { searches: 40, hashes: 2000, invites: 50, checks: 100 };

// C2 (approved by the founder 2026-10-13): the one list, which both apps
// bundle as it is. reserved: the whole handle; anywhere: inside it; whole:
// the whole handle or one of its parts; raw: as typed.
const LIST = JSON.parse(readFileSync(new URL('./blocklist/usernames.json', import.meta.url), 'utf8'));
const LEET = { 0: 'o', 1: 'i', 3: 'e', 4: 'a', 5: 's', 7: 't', 8: 'b' };
const leet = (s) => s.replace(/[0134578]/g, (d) => LEET[d]);

/** A term as a pattern: each run of n equal letters may repeat, never
 *  shrink (boobs is b+o{2,}b+s+), so bobsled stays free. */
export function termPattern(term) {
  return term.replace(/_/g, '').replace(/(.)\1*/g, (run, c) =>
    c.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + (run.length > 1 ? `{${run.length},}` : '+'));
}

const anchored = (terms) => terms.map((t) => new RegExp(`^${termPattern(t)}$`));
const RESERVED = anchored(LIST.reserved);
const ANYWHERE = LIST.anywhere.map((t) => new RegExp(termPattern(t)));
const WHOLE = anchored(LIST.whole);

/** Whether a well-formed handle is refused. Never says which term matched. */
export function blocked(handle) {
  if (LIST.raw.some((t) => handle.includes(t))) return true;
  const full = leet(handle).replace(/_/g, '');
  const bare = handle.replace(/[0-9_]/g, '');
  const parts = [...handle.split('_').filter(Boolean).map(leet), ...handle.split(/[0-9_]+/).filter(Boolean)];
  if (RESERVED.some((r) => r.test(full) || r.test(bare))) return true;
  if (ANYWHERE.some((r) => r.test(full) || r.test(bare))) return true;
  return WHOLE.some((r) => [full, bare, ...parts].some((v) => r.test(v)));
}

/** A handle as stored: trimmed, without a leading @, lower case; or the
 *  reason it cannot be one. */
export function username(raw) {
  if (typeof raw !== 'string') return { error: 'missing' };
  const handle = raw.trim().replace(/^@/, '').toLowerCase();
  if (!USERNAME.test(handle)) return { error: 'shape' };
  if (blocked(handle)) return { error: 'unavailable' };
  return { handle };
}

/** C2: set once free, changed once, never removed. */
export function usernameStep(old, next, changed) {
  if (next === old) return { same: true };
  if (next === null) return { error: 'A username can be changed, not removed.' };
  if (old === null) return { first: true };
  if (changed) return { error: 'Your username was already changed once.' };
  return { change: true };
}

/** C2: candidate handles from a name, best first: well formed, never
 *  blocked, no repeats. `year2` is the year's last two digits. */
export function suggestions(name, year2) {
  if (typeof name !== 'string') return [];
  const words = name.normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase()
    .split(/[^a-z0-9]+/).filter(Boolean);
  if (!words.length) return [];
  const f = words[0];
  const l = words.length > 1 ? words[words.length - 1] : null;
  const yy = String(year2).padStart(2, '0');
  const raw = l
    ? [f, f + l, `${f}_${l}`, f[0] + l, `${f}_${l[0]}`, l + f, `${f}${yy}`, `${f}_${yy}`]
    : [f, `${f}${yy}`, `${f}_${yy}`, `its_${f}`];
  const out = [];
  for (const c of raw.map((c) => c.slice(0, 20))) {
    if (USERNAME.test(c) && !blocked(c) && !out.includes(c)) out.push(c);
  }
  return out;
}
```

In `charge`, add `checks: 0` to the fresh object:

```js
  const fresh = state?.day === today ? { ...state } : { day: today, searches: 0, hashes: 0, invites: 0, checks: 0 };
```

Remove `export const RESERVED = new Set([...]);` entirely (the list replaces it).

- [ ] **Step 5: Run to see them pass**

Run: `node --test firebase/tests/people.test.mjs`
Expected: PASS, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add firebase/functions/blocklist/usernames.json firebase/functions/people.js firebase/tests/people.test.mjs
git commit -m "C2: the username blocklist and its rule in the cloud (pure, goldens) [skip ci]"
```

### Task 2: The callables (setUsername rules, checkUsername, suggestUsernames)

**Files:**
- Modify: `firebase/functions/index.js:203-226` (`setUsername`), add two callables after it
- Test: `firebase/tests/functions.test.mjs` (the two existing username tests, the find test's setup, new tests), `firebase/tests/rules.test.mjs`

**Interfaces:**
- Consumes: Task 1's `people.username`, `people.usernameStep`, `people.suggestions`, `charge(uid, 'checks')`.
- Produces: `setUsername({username}) -> {username, canChange}`; `checkUsername({username}) -> {available, reason?: 'shape'|'unavailable'|'taken'}`; `suggestUsernames({name}) -> {usernames: string[]}` (≤ 3). Errors: `FAILED_PRECONDITION` (profile not ready / removing / no change left), `INVALID_ARGUMENT` (shape or unavailable), `ALREADY_EXISTS` (taken), `RESOURCE_EXHAUSTED`.

- [ ] **Step 1: Write the failing emulator tests**

Replace the test `'a username is claimed once, case-insensitively; changing it frees the old one'` with:

```js
const profile = (uid) => seed(`users/${uid}`, { name: uid, participantId: '9'.repeat(32) });

test('a username is set once, changed once, never removed; the old one is free at once', async () => {
  await profile('priya'); await profile('eve');
  assert.deepEqual((await call('setUsername', 'priya', { username: '@Priya' })).result, { username: 'priya', canChange: true });
  assert.equal((await read('usernames/priya')).uid.stringValue, 'priya');
  assert.deepEqual(await call('setUsername', 'eve', { username: 'PRIYA' }), { error: 'ALREADY_EXISTS' });
  assert.deepEqual((await call('setUsername', 'priya', { username: 'priya_r' })).result, { username: 'priya_r', canChange: false });
  assert.equal((await read('users/priya')).usernameChanged.booleanValue, true);
  assert.equal(await read('usernames/priya'), null, 'the old handle is free at once');
  assert.deepEqual((await call('setUsername', 'eve', { username: 'priya' })).result, { username: 'priya', canChange: true });
  assert.deepEqual(await call('setUsername', 'priya', { username: 'priya_x' }), { error: 'FAILED_PRECONDITION' }, 'one change only');
  assert.deepEqual(await call('setUsername', 'priya', { username: null }), { error: 'FAILED_PRECONDITION' }, 'never removed');
  assert.equal((await read('users/priya')).username.stringValue, 'priya_r');
  assert.deepEqual((await call('setUsername', 'priya', { username: 'priya_r' })).result, { username: 'priya_r', canChange: false },
    'the same handle again is no change');
});

test('a username waits for the profile (participantId first), and is never set signed out', async () => {
  assert.deepEqual(await call('setUsername', 'nobody', { username: 'nobody1' }), { error: 'FAILED_PRECONDITION' });
  assert.equal(await read('users/nobody'), null, 'no half profile is made');
  assert.deepEqual(await call('setUsername', null, { username: 'priya' }), { error: 'UNAUTHENTICATED' });
});
```

Replace `'a username must be well formed, not reserved, and set by a signed-in account'` with:

```js
test('a username must be well formed and not blocked or reserved', async () => {
  await profile('priya');
  for (const bad of ['ab', 'pri ya', 'x'.repeat(21), 'support', 'skipti_fan', 'b1tch', 'tech_support']) {
    assert.deepEqual(await call('setUsername', 'priya', { username: bad }), { error: 'INVALID_ARGUMENT' }, bad);
  }
});

test('checkUsername: free, yours, taken, malformed or not allowed; charged to the day\'s checks', async () => {
  await profile('priya');
  await call('setUsername', 'priya', { username: 'priya' });
  assert.deepEqual((await call('checkUsername', 'al', { username: '@Bo_7' })).result, { available: true });
  assert.deepEqual((await call('checkUsername', 'priya', { username: 'priya' })).result, { available: true }, 'your own');
  assert.deepEqual((await call('checkUsername', 'al', { username: 'PRIYA' })).result, { available: false, reason: 'taken' });
  assert.deepEqual((await call('checkUsername', 'al', { username: 'ab' })).result, { available: false, reason: 'shape' });
  assert.deepEqual((await call('checkUsername', 'al', { username: 'sh1t' })).result, { available: false, reason: 'unavailable' });
  assert.deepEqual(await call('checkUsername', null, { username: 'bo_7' }), { error: 'UNAUTHENTICATED' });
  await seed('limits/al', { day: new Date().toISOString().slice(0, 10), searches: 0, hashes: 0, invites: 0, checks: 100 });
  assert.deepEqual(await call('checkUsername', 'al', { username: 'bo_7' }), { error: 'RESOURCE_EXHAUSTED' });
});

test('suggestUsernames: the first three free handles from a name, your own counting as free', async () => {
  await profile('priya'); await profile('ps');
  await call('setUsername', 'priya', { username: 'prithvi' });
  await call('setUsername', 'ps', { username: 'psaha' });
  assert.deepEqual((await call('suggestUsernames', 'al', { name: 'Prithvi Saha' })).result,
    { usernames: ['prithvisaha', 'prithvi_saha', 'prithvi_s'] });
  assert.deepEqual((await call('suggestUsernames', 'priya', { name: 'Prithvi Saha' })).result,
    { usernames: ['prithvi', 'prithvisaha', 'prithvi_saha'] });
  assert.deepEqual((await call('suggestUsernames', 'al', { name: 'राहुल' })).result, { usernames: [] });
  assert.deepEqual(await call('suggestUsernames', 'al', { name: 42 }), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('suggestUsernames', 'al', { name: 'x'.repeat(81) }), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('suggestUsernames', null, { name: 'Al' }), { error: 'UNAUTHENTICATED' });
});
```

In `'people are found by username or email fingerprint, …'` add `await profile('priya');` as its first line (setUsername now needs the profile).

In `rules.test.mjs`, the test `'D-197: the phone writes the name others see, short; never its username, findable or email fingerprint'` gains the change counter (rename it to end "…, findable, email fingerprint or change counter"): its loop becomes

```js
  for (const field of [{ username: 'alice' }, { findable: false }, { emailHash: 'a'.repeat(64) }, { usernameChanged: false }]) {
    assert.equal(await write('alice', 'users/alice', { ...field, updatedAt: SERVER_TIME }, 'update'), DENIED, JSON.stringify(field));
  }
```
and after its `'not at create either'` line add:
```js
  assert.equal(await write('carol', 'users/carol', { ...profile(), usernameChanged: false }), DENIED, 'C2: the counter is Functions only');
```

- [ ] **Step 2: Run to see them fail**

Run: `zsh -lic 'cd /Users/prithvi/Documents/Skipti/firebase && node_modules/.bin/firebase emulators:exec --only firestore,storage,functions,auth --project demo-skipti "node --test --test-concurrency=1 tests/functions.test.mjs tests/rules.test.mjs"'`
Expected: the new and replaced username tests FAIL (no `canChange`, no `checkUsername`), the rules test PASSES already (the rules already allow only `name` and `updatedAt`; it is a guard, proved by sabotage later).

- [ ] **Step 3: Implement in `index.js`**

Replace `setUsername` with:

```js
// Your @username. Unique by the document id of usernames/{handle}. C2: set
// once free, changed once (usernameChanged, which only Functions write),
// never removed; the old handle is free at once; never before the profile
// exists (its participantId is written by the phone's registration).
export const setUsername = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const raw = request.data?.username;
  const wanted = raw == null || raw === '' ? null : people.username(raw);
  if (wanted?.error) throw new HttpsError('invalid-argument', 'username ' + wanted.error);
  const userRef = db.collection('users').doc(uid);
  return db.runTransaction(async (tx) => {
    const user = await tx.get(userRef);
    if (!user.exists) throw new HttpsError('failed-precondition', 'Your account is still being set up. Try again.');
    const data = user.data();
    const old = data.username ?? null;
    const changed = data.usernameChanged === true;
    const handle = wanted?.handle ?? null;
    const step = people.usernameStep(old, handle, changed);
    if (step.error) throw new HttpsError('failed-precondition', step.error);
    if (step.same) return { username: old, canChange: !changed };
    const taken = await tx.get(db.collection('usernames').doc(handle));
    if (taken.exists && taken.data().uid !== uid) throw new HttpsError('already-exists', 'That username is taken.');
    const oldRef = old ? db.collection('usernames').doc(old) : null;
    const oldDoc = oldRef ? await tx.get(oldRef) : null;
    tx.set(db.collection('usernames').doc(handle), { uid });
    if (oldDoc?.exists && oldDoc.data().uid === uid) tx.delete(oldRef);
    tx.set(userRef, { username: handle, ...(step.change ? { usernameChanged: true } : {}) }, { merge: true });
    return { username: handle, canChange: !(changed || step.change) };
  });
});

// C2: whether a username is free for you, while you type. Shape and the
// blocklist cost no read; never says which word matched.
export const checkUsername = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  await charge(uid, 'checks');
  const wanted = people.username(request.data?.username);
  if (wanted.error) return { available: false, reason: wanted.error === 'unavailable' ? 'unavailable' : 'shape' };
  const owner = await db.collection('usernames').doc(wanted.handle).get();
  if (owner.exists && owner.data().uid !== uid) return { available: false, reason: 'taken' };
  return { available: true };
});

// C2: up to three free usernames built from a name ("Suggested for you").
export const suggestUsernames = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const name = request.data?.name;
  if (typeof name !== 'string' || name.length > 80) throw new HttpsError('invalid-argument', 'name is not valid');
  await charge(uid, 'checks');
  const out = [];
  for (const handle of people.suggestions(name, new Date().getUTCFullYear() % 100)) {
    const owner = await db.collection('usernames').doc(handle).get();
    if (!owner.exists || owner.data().uid === uid) out.push(handle);
    if (out.length === 3) break;
  }
  return { usernames: out };
});
```

- [ ] **Step 4: Run to see them pass**

Same command as Step 2. Expected: PASS, 0 failures (record the counts).

- [ ] **Step 5: Commit**

```bash
git add firebase/functions/index.js firebase/tests/functions.test.mjs firebase/tests/rules.test.mjs
git commit -m "C2: setUsername once-and-once rules, checkUsername and suggestUsernames [skip ci]"
```

### Task 3: The rule on both phones (twinned, pure)

**Files:**
- Create: `ios/Skipti/App/UsernameRules.swift`, `ios/SkiptiTests/UsernameRulesTests.swift`
- Modify: `ios/Skipti.xcodeproj/project.pbxproj` (2 Swift files + 1 resource, §7.1: assert every new ID unused; copy sibling lines untruncated; the resource like `licences.json`: `path = ../firebase/functions/blocklist/usernames.json; sourceTree = SOURCE_ROOT;` in Resources)
- Create: `android/app/src/main/java/com/vardrlabs/skipti/UsernameRules.kt`, `android/app/src/test/java/com/vardrlabs/skipti/UsernameRulesTest.kt`
- Modify: `android/app/build.gradle.kts` (asset dir + JVM test input)
- Modify: `ios/Skipti/App/People.swift` (`usernameProblem` via the rules; delete `reserved`), `android/.../People.kt` (same; delete `RESERVED`), and the two old parity tests (`testUsernamesAreRefusedHereAsTheCloudWould`, `usernames_are_refused_here_as_the_cloud_would`): delete their people.js-parsing half, keep the four `usernameProblem` assertions.

**Interfaces:**
- Produces (Swift): `UsernameRules.Problem { shape, unavailable }`, `UsernameRules.List`, `UsernameRules.list: List?`, `UsernameRules.load(_ bundle: Bundle) -> List?`, `UsernameRules.handle(_:) -> String`, `UsernameRules.problem(_ raw: String, list: List? = list) -> Problem?`, `UsernameRules.pattern(_:) -> String`; `UsernameState { none, canChange(String), final(String) }` with `UsernameState.from(_ data: [String: Any]?) -> UsernameState?` (nil when there is no profile document).
- Produces (Kotlin): `UsernameRules.Problem`, `UsernameRules.TermList(reserved, anywhere, whole, raw)`, `UsernameRules.parse(text): TermList`, `UsernameRules.list` (set by `load(context)`), `UsernameRules.problem(raw, list = this.list): Problem?`, `UsernameRules.pattern(term)`; `sealed class UsernameState { None; CanChange(handle); Final(handle) }` with `UsernameState.from(data: Map<String, Any?>?): UsernameState?`.

- [ ] **Step 1: Write the failing tests**

`ios/SkiptiTests/UsernameRulesTests.swift`:

```swift
import XCTest
@testable import Skipti

/// C2: the phone refuses a username as the cloud would (the same list, the
/// same goldens, re-derived by .drafts/c2/golden.py from the spec's words).
final class UsernameRulesTests: XCTestCase {
    static let allowed = ["classic", "peacock", "grape", "assam", "method", "prithvi_saha", "kshitij", "nazir",
                          "therapist", "badminton", "pineapple", "spicy", "bobsled", "tunnel", "dallas", "brandi",
                          "sunni_muslim", "team_rocket", "me_and_you", "supportive_sam", "pakistani", "hello", "@Priya_92"]
    static let refused = ["scunthorpe", "f_u_c_k", "fuuuck", "b1tch", "dick69", "support", "supp0rt", "tech_support",
                          "skipti_fan", "x_spic_99", "n4zi", "sh1t", "bullsh1t", "booooobs", "help", "x1488x", "asss",
                          "vardr_labs", "SKIPTI"]
    static let badShape = ["me", "ab", "pri ya", String(repeating: "a", count: 21)]

    func testTheBundledListIsTheCloudsFile() throws {
        let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("firebase/functions/blocklist/usernames.json")
        let bundled = try XCTUnwrap(Bundle.main.url(forResource: "usernames", withExtension: "json"))
        XCTAssertEqual(try Data(contentsOf: bundled), try Data(contentsOf: repo))
        XCTAssertNotNil(UsernameRules.list)
    }

    func testTheGoldens() throws {
        let list = try XCTUnwrap(UsernameRules.list)
        for ok in Self.allowed { XCTAssertNil(UsernameRules.problem(ok, list: list), ok) }
        for no in Self.refused { XCTAssertEqual(UsernameRules.problem(no, list: list), .unavailable, no) }
        for bad in Self.badShape { XCTAssertEqual(UsernameRules.problem(bad, list: list), .shape, bad) }
    }

    func testATermsLettersMayRepeatButNeverShrink() {
        XCTAssertEqual(UsernameRules.pattern("boobs"), "b+o{2,}b+s+")
        XCTAssertEqual(UsernameRules.pattern("aai_ghal"), "a{2,}i+g+h+a+l+")
    }

    func testTheAccountsUsernameState() {
        XCTAssertNil(UsernameState.from(nil))
        XCTAssertEqual(UsernameState.from([:]), UsernameState.none)
        XCTAssertEqual(UsernameState.from(["username": "al"]), .canChange("al"))
        XCTAssertEqual(UsernameState.from(["username": "al", "usernameChanged": false]), .canChange("al"))
        XCTAssertEqual(UsernameState.from(["username": "al", "usernameChanged": true]), .final("al"))
        XCTAssertEqual(UsernameState.from(["username": ""]), UsernameState.none)
    }
}
```

`android/app/src/test/java/com/vardrlabs/skipti/UsernameRulesTest.kt`:

```kotlin
package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** C2: the twin of iOS's UsernameRulesTests (same list file, same goldens). */
class UsernameRulesTest {
    private val list = UsernameRules.parse(
        File(System.getProperty("user.dir")).resolve("../../firebase/functions/blocklist/usernames.json").readText(),
    )
    private val allowed = listOf("classic", "peacock", "grape", "assam", "method", "prithvi_saha", "kshitij", "nazir",
        "therapist", "badminton", "pineapple", "spicy", "bobsled", "tunnel", "dallas", "brandi", "sunni_muslim",
        "team_rocket", "me_and_you", "supportive_sam", "pakistani", "hello", "@Priya_92")
    private val refused = listOf("scunthorpe", "f_u_c_k", "fuuuck", "b1tch", "dick69", "support", "supp0rt",
        "tech_support", "skipti_fan", "x_spic_99", "n4zi", "sh1t", "bullsh1t", "booooobs", "help", "x1488x", "asss",
        "vardr_labs", "SKIPTI")
    private val badShape = listOf("me", "ab", "pri ya", "a".repeat(21))

    @Test
    fun the_goldens() {
        allowed.forEach { assertNull(it, UsernameRules.problem(it, list)) }
        refused.forEach { assertEquals(it, UsernameRules.Problem.UNAVAILABLE, UsernameRules.problem(it, list)) }
        badShape.forEach { assertEquals(it, UsernameRules.Problem.SHAPE, UsernameRules.problem(it, list)) }
    }

    @Test
    fun a_terms_letters_may_repeat_but_never_shrink() {
        assertEquals("b+o{2,}b+s+", UsernameRules.pattern("boobs"))
        assertEquals("a{2,}i+g+h+a+l+", UsernameRules.pattern("aai_ghal"))
    }

    @Test
    fun the_accounts_username_state() {
        assertNull(UsernameState.from(null))
        assertEquals(UsernameState.None, UsernameState.from(emptyMap()))
        assertEquals(UsernameState.CanChange("al"), UsernameState.from(mapOf("username" to "al")))
        assertEquals(UsernameState.CanChange("al"), UsernameState.from(mapOf("username" to "al", "usernameChanged" to false)))
        assertEquals(UsernameState.Final("al"), UsernameState.from(mapOf("username" to "al", "usernameChanged" to true)))
        assertEquals(UsernameState.None, UsernameState.from(mapOf("username" to "")))
    }
}
```

- [ ] **Step 2: Run to see them fail**

iOS (compile failure is expected first: the types do not exist; that is this step's red, the sabotage later proves the assertions bite): `xcodebuild -project ios/Skipti.xcodeproj -scheme Skipti -derivedDataPath ios/build -destination "platform=iOS Simulator,id=AFAF4221-F35B-4CD6-8ED9-A4AFD84C6D51" -collect-test-diagnostics never test -only-testing:SkiptiTests/UsernameRulesTests`
JVM: `zsh -lic 'cd /Users/prithvi/Documents/Skipti/android && ./gradlew :app:testPlayDebugUnitTest --tests com.vardrlabs.skipti.UsernameRulesTest'`

- [ ] **Step 3: Implement `ios/Skipti/App/UsernameRules.swift`**

```swift
import Foundation

/// C2: the username rule as the cloud decides it (firebase/functions/people.js),
/// so the phone answers at once. The list is the cloud's own file
/// (firebase/functions/blocklist/usernames.json), bundled as it is.
enum UsernameRules {
    enum Problem: Equatable { case shape, unavailable }

    struct List: Decodable, Equatable {
        let reserved: [String]
        let anywhere: [String]
        let whole: [String]
        let raw: [String]
    }

    static let list: List? = load(Bundle.main)

    static func load(_ bundle: Bundle) -> List? {
        guard let url = bundle.url(forResource: "usernames", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(List.self, from: data)
    }

    /// Trimmed, without a leading @, lower case.
    static func handle(_ raw: String) -> String {
        let t = raw.trimmingCharacters(in: .whitespaces)
        return (t.hasPrefix("@") ? String(t.dropFirst()) : t).lowercased()
    }

    /// Why a username cannot be had; nil when the cloud alone can say (taken).
    static func problem(_ raw: String, list: List? = list) -> Problem? {
        let h = handle(raw)
        guard h.range(of: "^[a-z0-9_]{3,20}$", options: .regularExpression) != nil else { return .shape }
        if let list, blocked(h, list: list) { return .unavailable }
        return nil
    }

    /// Each run of n equal letters may repeat, never shrink: boobs is b+o{2,}b+s+.
    static func pattern(_ term: String) -> String {
        let chars = Array(term.replacingOccurrences(of: "_", with: ""))
        var out = ""
        var i = 0
        while i < chars.count {
            var j = i
            while j < chars.count && chars[j] == chars[i] { j += 1 }
            out += NSRegularExpression.escapedPattern(for: String(chars[i])) + (j - i > 1 ? "{\(j - i),}" : "+")
            i = j
        }
        return out
    }

    private static let leetMap: [Character: Character] = ["0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t", "8": "b"]
    private static func leet(_ s: String) -> String { String(s.map { leetMap[$0] ?? $0 }) }
    private static func matches(_ term: String, _ s: String, whole: Bool) -> Bool {
        let p = pattern(term)
        return s.range(of: whole ? "^" + p + "$" : p, options: .regularExpression) != nil
    }

    static func blocked(_ h: String, list: List) -> Bool {
        if list.raw.contains(where: { h.contains($0) }) { return true }
        let full = leet(h).replacingOccurrences(of: "_", with: "")
        let bare = h.replacingOccurrences(of: "[0-9_]", with: "", options: .regularExpression)
        let parts = h.split(separator: "_").map { leet(String($0)) }
            + h.split(whereSeparator: { $0 == "_" || $0.isASCII && $0.isNumber }).map(String.init)
        if list.reserved.contains(where: { t in [full, bare].contains { matches(t, $0, whole: true) } }) { return true }
        if list.anywhere.contains(where: { t in [full, bare].contains { matches(t, $0, whole: false) } }) { return true }
        return list.whole.contains(where: { t in ([full, bare] + parts).contains { matches(t, $0, whole: true) } })
    }
}

/// C2: the account's username, read from its cloud profile.
enum UsernameState: Equatable {
    case none
    case canChange(String)
    case final(String)

    /// nil: no profile document (not signed in, offline, or not registered yet).
    static func from(_ data: [String: Any]?) -> UsernameState? {
        guard let data else { return nil }
        guard let h = data["username"] as? String, !h.isEmpty else { return UsernameState.none }
        return data["usernameChanged"] as? Bool == true ? .final(h) : .canChange(h)
    }
}
```

In `People.swift` replace `usernameProblem` and delete `static let reserved`:

```swift
    /// Why a username cannot be saved, before asking the cloud (C2: the
    /// cloud's own list, bundled).
    static func usernameProblem(_ raw: String) -> String? {
        switch UsernameRules.problem(raw) {
        case .shape?: return PeopleCopy.usernameShape
        case .unavailable?: return PeopleCopy.usernameReserved
        case nil: return nil
        }
    }
```

pbxproj: four insertions per Swift file (A/B ids asserted unused first; `UsernameRules.swift` and `UsernameStep.swift` are app files `path = Skipti/App/X.swift; sourceTree = SOURCE_ROOT;`, `UsernameRulesTests.swift` a test file `path = X.swift; sourceTree = "<group>";`), and the resource exactly like `licences.json` (`A10114`/`B10129` lines: PBXBuildFile `in Resources`, PBXFileReference with `lastKnownFileType = text.json; name = usernames.json; path = ../firebase/functions/blocklist/usernames.json; sourceTree = SOURCE_ROOT;`, the group child, and the Resources phase entry). `UsernameStep.swift` is added in Task 4; reserve its IDs now or add then.

- [ ] **Step 4: Implement `android/.../UsernameRules.kt`**

```kotlin
package com.vardrlabs.skipti

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** C2: the username rule as the cloud decides it (firebase/functions/people.js),
 *  so the phone answers at once. The list is the cloud's own file, bundled
 *  as an asset (build.gradle.kts). iOS's twin is UsernameRules.swift. */
object UsernameRules {
    enum class Problem { SHAPE, UNAVAILABLE }

    data class TermList(val reserved: List<String>, val anywhere: List<String>, val whole: List<String>, val raw: List<String>)

    @Volatile var list: TermList? = null
        private set

    fun load(context: Context) {
        if (list == null) list = runCatching {
            parse(context.assets.open("usernames.json").bufferedReader().use { it.readText() })
        }.getOrNull()
    }

    fun parse(text: String): TermList {
        val o = Json.parseToJsonElement(text).jsonObject
        fun words(key: String) = (o[key] as JsonArray).map { (it as JsonPrimitive).content }
        return TermList(words("reserved"), words("anywhere"), words("whole"), words("raw"))
    }

    private val shape = Regex("^[a-z0-9_]{3,20}$")

    fun handle(raw: String): String = raw.trim().removePrefix("@").lowercase()

    /** Why a username cannot be had; null when the cloud alone can say (taken). */
    fun problem(raw: String, list: TermList? = this.list): Problem? {
        val h = handle(raw)
        if (!shape.matches(h)) return Problem.SHAPE
        if (list != null && blocked(h, list)) return Problem.UNAVAILABLE
        return null
    }

    /** Each run of n equal letters may repeat, never shrink: boobs is b+o{2,}b+s+. */
    fun pattern(term: String): String {
        val chars = term.replace("_", "")
        val out = StringBuilder()
        var i = 0
        while (i < chars.length) {
            var j = i
            while (j < chars.length && chars[j] == chars[i]) j++
            out.append(Regex.escape(chars[i].toString()).let { if (it.startsWith("\\Q")) chars[i].toString() else it })
            out.append(if (j - i > 1) "{${j - i},}" else "+")
            i = j
        }
        return out.toString()
    }

    private val leetMap = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b')
    private fun leet(s: String) = s.map { leetMap[it] ?: it }.joinToString("")
    private fun matches(term: String, s: String, whole: Boolean): Boolean {
        val p = pattern(term)
        return if (whole) Regex("^$p$").matches(s) else Regex(p).containsMatchIn(s)
    }

    fun blocked(h: String, list: TermList): Boolean {
        if (list.raw.any { h.contains(it) }) return true
        val full = leet(h).replace("_", "")
        val bare = h.replace(Regex("[0-9_]"), "")
        val parts = h.split("_").filter { it.isNotEmpty() }.map(::leet) + h.split(Regex("[0-9_]+")).filter { it.isNotEmpty() }
        if (list.reserved.any { t -> listOf(full, bare).any { matches(t, it, true) } }) return true
        if (list.anywhere.any { t -> listOf(full, bare).any { matches(t, it, false) } }) return true
        return list.whole.any { t -> (listOf(full, bare) + parts).any { matches(t, it, true) } }
    }
}

/** C2: the account's username, read from its cloud profile. */
sealed class UsernameState {
    data object None : UsernameState()
    data class CanChange(val handle: String) : UsernameState()
    data class Final(val handle: String) : UsernameState()

    companion object {
        /** null: no profile document (not signed in, offline, or not registered yet). */
        fun from(data: Map<String, Any?>?): UsernameState? {
            if (data == null) return null
            val h = (data["username"] as? String)?.takeIf { it.isNotEmpty() } ?: return None
            return if (data["usernameChanged"] == true) Final(h) else CanChange(h)
        }
    }
}
```

Note on `pattern`: every term is lower-case letters, so escaping never changes a character; the `Regex.escape` guard keeps `\Q…\E` out of the pattern string the tests compare.

`People.kt`: delete `val RESERVED = setOf(...)` and replace `usernameProblem` with:

```kotlin
    /** Why a username cannot be saved, before asking the cloud (C2: the
     *  cloud's own list, bundled). */
    fun usernameProblem(raw: String): String? = when (UsernameRules.problem(raw)) {
        UsernameRules.Problem.SHAPE -> PeopleCopy.USERNAME_SHAPE
        UsernameRules.Problem.UNAVAILABLE -> PeopleCopy.USERNAME_RESERVED
        null -> null
    }
```

The old JVM test calls `People.usernameProblem("Support")`, which needs the list loaded: in that test (and wherever a JVM test needs it) call `UsernameRules.problem(raw, list)` with the parsed file instead, or make the old test parse the file first. Keep its four assertions; delete its people.js parsing.

Call `UsernameRules.load(context)` once in `MainActivity.onCreate` (beside the other start-up loads).

`build.gradle.kts`: in `sourceSets { getByName("main") { … } }` add
```kotlin
            // C2: the username blocklist, the cloud's own file (one list for all three).
            assets.srcDir(rootDir.parentFile.resolve("firebase/functions/blocklist"))
```
and in `unitTests.all { … }` add
```kotlin
            // C2: UsernameRulesTest reads the cloud's blocklist file.
            it.inputs.file(rootDir.parentFile.resolve("firebase/functions/blocklist/usernames.json"))
```

- [ ] **Step 5: Run to see them pass** (the two commands in Step 2). Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add ios/Skipti/App/UsernameRules.swift ios/SkiptiTests/UsernameRulesTests.swift ios/Skipti/App/People.swift ios/SkiptiTests/AccountTests.swift ios/Skipti.xcodeproj/project.pbxproj android/app/src/main/java/com/vardrlabs/skipti/UsernameRules.kt android/app/src/main/java/com/vardrlabs/skipti/People.kt android/app/src/main/java/com/vardrlabs/skipti/MainActivity.kt android/app/src/test/java/com/vardrlabs/skipti/UsernameRulesTest.kt android/app/src/test/java/com/vardrlabs/skipti/AccountTest.kt android/app/build.gradle.kts
git commit -m "C2: the username rule on both phones, from the cloud's own list [skip ci]"
```

### Task 4: iOS: the step after sign-in, the change with its confirm, the card

**Files:**
- Create: `ios/Skipti/App/UsernameStep.swift` (+ pbxproj)
- Modify: `ios/Skipti/App/People.swift` (cloud calls; FindMeCard by state; delete `UsernameSheet` and the `removeUsername` copy), `ios/Skipti/App/SignInScreen.swift`, `ios/Skipti/App/OverviewScreen.swift`, `ios/Skipti/App/AccountHubScreen.swift`

**Interfaces:**
- Consumes: Task 2's callables; Task 3's `UsernameRules`, `UsernameState`.
- Produces: `People.accountUsername() async -> UsernameState?`, `People.checkUsername(_:) async throws -> UsernameAvailability`, `People.suggestUsernames(name:) async -> [String]`, `People.setUsername(_:) async throws -> String?` (unchanged signature), views `UsernameStep(store:onDone:)`, `UsernameChangeSheet(current:onSaved:)`, `UsernameCard(store:)`.

- [ ] **Step 1: The cloud calls in `People.swift`** (beside `myUsername`)

```swift
    /// C2: the account's username and whether its one change is left; nil
    /// when there is no profile to read (signed out, offline, not registered).
    @MainActor
    static func accountUsername() async -> UsernameState? {
        guard let uid = Account.shared.uid,
              let doc = try? await Cloud.db.collection("users").document(uid).getDocument(source: .server),
              doc.exists else { return nil }
        return UsernameState.from(doc.data())
    }

    enum UsernameAvailability: Equatable { case available, taken, shape, unavailable }

    /// C2: the live check while typing.
    @MainActor
    static func checkUsername(_ raw: String) async throws -> UsernameAvailability {
        let result = try await CloudFunctions.call("checkUsername", ["username": raw])
        if result["available"] as? Bool == true { return .available }
        switch result["reason"] as? String {
        case "taken": return .taken
        case "shape": return .shape
        default: return .unavailable
        }
    }

    /// C2: up to three free usernames from a name; [] on any failure.
    @MainActor
    static func suggestUsernames(name: String) async -> [String] {
        guard let result = try? await CloudFunctions.call("suggestUsernames", ["name": name]) else { return [] }
        return (result["usernames"] as? [String] ?? []).filter { UsernameRules.problem($0) == nil }
    }
```

`People.failure` is unchanged: the username screens map their own refusals (below).

- [ ] **Step 2: `UsernameStep.swift`**

```swift
import SwiftUI

/// C2 copy (the approved mock-up, v2). Android's twin is UsernameCopy in UsernameStep.kt.
enum UsernameCopy {
    static let title = "Pick your username"
    static let lede = "People find and add you by it. You can change it once later."
    static let yourName = "Your name"
    static let namePrompt = "What friends call you"
    static let username = "Username"
    static let prompt = "yourname"
    static let hint = "3 to 20 letters, numbers or _"
    static let checking = "Checking…"
    static func available(_ h: String) -> String { "@\(h) is available" }
    static func taken(_ h: String) -> String { "@\(h) is taken. Try one below." }
    static let notAvailable = "That username is not available."
    static let suggested = "Suggested for you"
    static let save = "Save username"
    static let offline = "Could not reach SKIPTI's cloud. Check your connection and try again."
    static func confirmTitle(_ h: String) -> String { "Change to @\(h)?" }
    static func confirmBody(new: String, old: String) -> String {
        "You can change your username once. After this, @\(new) is yours for good, and @\(old) is free for anyone to take."
    }
    static let change = "Change username"
    static func keep(_ h: String) -> String { "Keep @\(h)" }
    static let oneChangeLeft = "1 change left"
    static let changedOnce = "Changed once, it stays now"
    static let cardTitle = "Pick your username"
    static let cardBody = "People can find and add you by it. It takes a few seconds."
    static let cardAction = "Pick username"
}

/// The live status under the field.
enum UsernameStatus: Equatable {
    case hint, checking, available(String), taken(String), notAvailable, offline

    /// What the phone can say at once, before any cloud check.
    static func local(_ raw: String) -> UsernameStatus? {
        let h = UsernameRules.handle(raw)
        if h.count < 3 { return .hint }
        switch UsernameRules.problem(raw) {
        case .shape?: return .hint
        case .unavailable?: return .notAvailable
        case nil: return nil
        }
    }
}

/// The @username field, its live status and the three suggestions; shared
/// by the step after sign-in and the change sheet.
struct UsernameField: View {
    @Binding var text: String
    @Binding var status: UsernameStatus
    let suggestions: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: UsernameCopy.username).font(TypeRole.microLabel()).foregroundStyle(Color(Tokens.textDim))
            HStack(spacing: 2) {
                Text(verbatim: "@").font(TypeRole.body()).foregroundStyle(Tokens.accent)
                TextField(UsernameCopy.prompt, text: $text)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(TypeRole.body())
                    .accessibilityLabel(UsernameCopy.username)
            }
            .padding(12)
            .glassCard()
            statusLine
            if !suggestions.isEmpty {
                Text(verbatim: UsernameCopy.suggested).font(TypeRole.microLabel()).foregroundStyle(Color(Tokens.textDim))
                HStack(spacing: 8) {
                    ForEach(suggestions, id: \.self) { s in
                        Button("@" + s) { text = s }.buttonStyle(SecondaryButtonStyle())
                    }
                }
            }
        }
        .task(id: text) {
            if let now = UsernameStatus.local(text) { status = now; return }
            status = .checking
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            let h = UsernameRules.handle(text)
            do {
                switch try await People.checkUsername(h) {
                case .available: status = .available(h)
                case .taken: status = .taken(h)
                case .shape: status = .hint
                case .unavailable: status = .notAvailable
                }
            } catch {
                if !Task.isCancelled { status = .offline }
            }
        }
    }

    @ViewBuilder private var statusLine: some View {
        switch status {
        case .hint: Text(verbatim: UsernameCopy.hint).font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
        case .checking: Text(verbatim: UsernameCopy.checking).font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
        case .available(let h): Text(verbatim: "✓ " + UsernameCopy.available(h)).font(TypeRole.body(13)).foregroundStyle(Tokens.owing)
        case .taken(let h): Text(verbatim: UsernameCopy.taken(h)).font(TypeRole.body(13)).foregroundStyle(Tokens.owed)
        case .notAvailable: Text(verbatim: UsernameCopy.notAvailable).font(TypeRole.body(13)).foregroundStyle(Tokens.owed)
        case .offline: Text(verbatim: UsernameCopy.offline).font(TypeRole.body(13)).foregroundStyle(Tokens.owed)
        }
    }
}

/// C2: right after sign-in, for an account with no username. Required: no
/// close button. Asks your name first when this phone has none.
struct UsernameStep: View {
    let store: LedgerStore
    var onDone: () -> Void
    @State private var name = ""
    @State private var needsName = false
    @State private var text = ""
    @State private var status = UsernameStatus.hint
    @State private var suggestions: [String] = []
    @State private var saving = false
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(verbatim: UsernameCopy.lede).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                if needsName {
                    Text(verbatim: UsernameCopy.yourName).font(TypeRole.microLabel()).foregroundStyle(Color(Tokens.textDim))
                    TextField(UsernameCopy.namePrompt, text: $name)
                        .textContentType(.name)
                        .font(TypeRole.body())
                        .padding(12)
                        .glassCard()
                }
                UsernameField(text: $text, status: $status, suggestions: suggestions)
                if let error {
                    Text(verbatim: error).font(TypeRole.body(14)).foregroundStyle(Tokens.owed)
                }
            }
            .padding(20)
        }
        .formActionBar {
            Button(UsernameCopy.save) { Task { await save() } }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(saving || !canSave)
        }
        .auroraBackground()
        .navigationTitle(UsernameCopy.title)
        .navigationBarBackButtonHidden()
        .interactiveDismissDisabled()
        .task { needsName = ((try? store.loadSelf())?.name ?? "").isEmpty }
        .task(id: needsName ? name : "self") {
            let source = needsName ? name : ((try? store.loadSelf())?.name ?? "")
            guard !source.trimmingCharacters(in: .whitespaces).isEmpty else { suggestions = []; return }
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            suggestions = await People.suggestUsernames(name: source)
        }
    }

    private var canSave: Bool {
        if case .available = status { return !needsName || !name.trimmingCharacters(in: .whitespaces).isEmpty }
        return false
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            if needsName { _ = try store.setYourName(name) }
            guard let uid = Account.shared.uid else { throw CloudFunctions.Failure.signedOut }
            // The profile (and its participantId) first: setUsername refuses
            // until it exists, and must never make a half one.
            _ = await CloudProfile.register(uid: uid, store: store)
            _ = try await People.setUsername(UsernameRules.handle(text))
            onDone()
        } catch CloudFunctions.Failure.refused("ALREADY_EXISTS") {
            status = .taken(UsernameRules.handle(text))
        } catch CloudFunctions.Failure.refused("INVALID_ARGUMENT") {
            status = .notAvailable
        } catch {
            self.error = UsernameCopy.offline
        }
    }
}

/// C2: the one change, from You. Asks first.
struct UsernameChangeSheet: View {
    let current: String
    let store: LedgerStore
    var onSaved: (String) -> Void
    @State private var text = ""
    @State private var status = UsernameStatus.hint
    @State private var suggestions: [String] = []
    @State private var confirming = false
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                UsernameField(text: $text, status: $status, suggestions: suggestions)
                if let error { Text(verbatim: error).font(TypeRole.body(14)).foregroundStyle(Tokens.owed) }
            }
            .padding(20)
        }
        .formActionBar {
            Button(UsernameCopy.change) { confirming = true }
                .buttonStyle(PrimaryButtonStyle())
                .disabled({ if case .available(let h) = status { return h == current }; return true }())
        }
        .auroraBackground()
        .navigationTitle(UsernameCopy.change)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        .alert(UsernameCopy.confirmTitle(UsernameRules.handle(text)), isPresented: $confirming) {
            Button(UsernameCopy.change) { Task { await save() } }
            Button(UsernameCopy.keep(current), role: .cancel) {}
        } message: {
            Text(verbatim: UsernameCopy.confirmBody(new: UsernameRules.handle(text), old: current))
        }
        .task {
            let name = (try? store.loadSelf())?.name ?? ""
            if !name.isEmpty { suggestions = await People.suggestUsernames(name: name) }
        }
    }

    private func save() async {
        do {
            let saved = try await People.setUsername(UsernameRules.handle(text)) ?? current
            onSaved(saved)
        } catch CloudFunctions.Failure.refused("ALREADY_EXISTS") {
            status = .taken(UsernameRules.handle(text))
        } catch CloudFunctions.Failure.refused("INVALID_ARGUMENT") {
            status = .notAvailable
        } catch {
            self.error = UsernameCopy.offline
        }
    }
}

/// C2: for an account signed in before usernames were asked: a card on Home
/// and You, never a screen in the way (GUARANTEES #2).
struct UsernameCard: View {
    let store: LedgerStore
    @State private var state: UsernameState?
    @State private var picking = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if state == UsernameState.none {
                Text(verbatim: UsernameCopy.cardTitle).font(TypeRole.label(16)).foregroundStyle(Tokens.text)
                Text(verbatim: UsernameCopy.cardBody).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
                Button(UsernameCopy.cardAction) { picking = true }.buttonStyle(PrimaryButtonStyle())
            }
        }
        .modifier(CardWhen(shown: state == UsernameState.none))
        .task { state = Account.shared.isSignedIn ? await People.accountUsername() : nil }
        .sheet(isPresented: $picking) {
            NavigationStack {
                UsernameStep(store: store) {
                    picking = false
                    state = .canChange("")
                }
            }
        }
    }
}

/// The card's glass only when it has something to show (an empty card would
/// still take its spacing). The `.task` above sits on the always-present
/// VStack, never on an empty Group (CLAUDE.md §10.1).
private struct CardWhen: ViewModifier {
    let shown: Bool
    func body(content: Content) -> some View {
        if shown { content.glassCard() } else { content.frame(height: 0) }
    }
}
```

Note for the implementer: a zero-height VStack in a parent stack still takes the parent's spacing (D-208's finding). Place `UsernameCard` where that is acceptable, or LOOK at it and, if a gap shows, move the `.task` to the parent and pass the state in, as GroupsScreen does with `joiningInvite`.

- [ ] **Step 3: Sign-in (`SignInScreen.swift`)**

Add `@State private var usernameNeeded: Bool?` and change `body`'s first branch and `finish`:

```swift
        if let signedIn {
            switch usernameNeeded {
            case true?:
                // C2: required, no close; then the usual confirmation.
                UsernameStep(store: .defaultStore()) { usernameNeeded = false }
            case false?:
                SignedInConfirmation(method: signedIn) { dismiss() }
                    .navigationTitle("Sign in")
                    .navigationBarBackButtonHidden()
            case nil:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity).auroraBackground()
                    .navigationBarBackButtonHidden()
            }
        } else { … unchanged … }
```

In `finish`, `case .signedIn:` becomes:

```swift
        case .signedIn:
            refused = false
            signedIn = method
            Task {
                // An account that already has a username (a second phone)
                // skips the step. Right after sign-in the profile may not
                // exist yet (registration runs in the background): register
                // it here, then read again. Still unreadable: no step; the
                // card asks later. (No `await` inside `&&`: Swift refuses it.)
                var state = await People.accountUsername()
                if state == nil, let uid = Account.shared.uid {
                    _ = await CloudProfile.register(uid: uid, store: .defaultStore())
                    state = await People.accountUsername()
                }
                usernameNeeded = state == UsernameState.none
            }
```

- [ ] **Step 4: FindMeCard by state (`People.swift`)**

Replace `@State private var username: String?` with `@State private var state: UsernameState?`, `@State private var picking = false`, `@State private var changing = false`, and the username row + button with:

```swift
            HStack {
                Text(verbatim: PeopleCopy.username).font(TypeRole.body()).foregroundStyle(Tokens.text)
                Spacer()
                switch state {
                case .canChange(let h)?, .final(let h)?:
                    Text(verbatim: PeopleCopy.handle(h)).font(TypeRole.body()).foregroundStyle(Tokens.text)
                default:
                    Text(verbatim: PeopleCopy.noUsername).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                }
            }
            switch state {
            case .canChange?:
                Text(verbatim: UsernameCopy.oneChangeLeft).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
                Button(PeopleCopy.changeUsername) { changing = true }.buttonStyle(SecondaryButtonStyle())
            case .final?:
                Text(verbatim: UsernameCopy.changedOnce).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
            case UsernameState.none?:
                Button(PeopleCopy.chooseUsername) { picking = true }.buttonStyle(SecondaryButtonStyle())
            case nil:
                EmptyView()
            }
```

and the sheet (replacing `.sheet(isPresented: $editing)`):

```swift
        .sheet(isPresented: $changing) {
            if case .canChange(let h)? = state {
                NavigationStack {
                    UsernameChangeSheet(current: h, store: store) { saved in
                        state = .final(saved)
                        changing = false
                    }
                }
            }
        }
        .sheet(isPresented: $picking) {
            NavigationStack {
                UsernameStep(store: store) {
                    picking = false
                    Task { state = await People.accountUsername() }
                }
            }
        }
```

`load()` sets `state = await People.accountUsername()`. Delete `struct UsernameSheet` and `PeopleCopy.removeUsername`.

- [ ] **Step 5: The cards**

`OverviewScreen.swift`, under `HomeGreeting(store: store)`: `UsernameCard(store: store)`.
`AccountHubScreen.swift`, under `YouHero(…)`: `UsernameCard(store: LedgerStore(path: vaultPath))`.

- [ ] **Step 6: Build, then look (simulator, iOS 27; CLAUDE.md §7: boot fully, then launch)**

Run: `xcodebuild -project ios/Skipti.xcodeproj -scheme Skipti -derivedDataPath ios/build -destination "platform=iOS Simulator,id=AFAF4221-F35B-4CD6-8ED9-A4AFD84C6D51" build`
Look (production cloud is NOT touched by the look until deploy; use the emulators: launch with `--emulators`, sign in through the Auth emulator): the step after sign-in (with and without a name), typing (checking / available / taken / not available), a chip tap, Save; You's row "1 change left", the change, the confirm, the row after; the Home card for an account without a username. Screenshots to `.drafts/c2/`.

- [ ] **Step 7: Commit**

```bash
git add ios/Skipti/App/UsernameStep.swift ios/Skipti/App/People.swift ios/Skipti/App/SignInScreen.swift ios/Skipti/App/OverviewScreen.swift ios/Skipti/App/AccountHubScreen.swift ios/Skipti.xcodeproj/project.pbxproj
git commit -m "C2: iOS username step after sign-in, the one change with its confirm, the card [skip ci]"
```

### Task 5: Android twin of Task 4

**Files:**
- Create: `android/app/src/main/java/com/vardrlabs/skipti/UsernameStep.kt`
- Modify: `People.kt` (cloud calls; FindMeCard by state; delete `UsernameEditor`, `REMOVE_USERNAME`), `AppRoot.kt` (sign-in route; a `username` route for the card), `Profile.kt` (`HomeGreeting` gets the card), the You screen file that draws `YouHero` (card under it)

**Interfaces:**
- Consumes: Task 2, Task 3 (`UsernameRules`, `UsernameState`).
- Produces: `People.accountUsername(): UsernameState?`, `People.checkUsername(raw): UsernameAvailability`, `People.suggestUsernames(name): List<String>`; composables `UsernameStep(vaultPath, dark, onDone)`, `UsernameChangeScreen(current, vaultPath, dark, onSaved, onBack)`, `UsernameCard(vaultPath, dark, onPick)`; `UsernameCopy` with the same strings as iOS.

- [ ] **Step 1: Cloud calls in `People.kt`** (beside `myUsername`)

```kotlin
    /** C2: the account's username and whether its one change is left; null
     *  when there is no profile to read (signed out, offline, not registered). */
    suspend fun accountUsername(): UsernameState? {
        val uid = Account.uid() ?: return null
        return runCatching {
            val doc = Cloud.db().collection("users").document(uid).get(Source.SERVER).awaitTask()
            if (doc.exists()) UsernameState.from(doc.data) else null
        }.getOrNull()
    }

    enum class UsernameAvailability { AVAILABLE, TAKEN, SHAPE, UNAVAILABLE }

    suspend fun checkUsername(raw: String): UsernameAvailability {
        val r = CloudFunctions.callJson("checkUsername", JsonObject(mapOf("username" to JsonPrimitive(raw))))
        if ((r["available"] as? JsonPrimitive)?.booleanOrNull == true) return UsernameAvailability.AVAILABLE
        return when (r.str("reason")) {
            "taken" -> UsernameAvailability.TAKEN
            "shape" -> UsernameAvailability.SHAPE
            else -> UsernameAvailability.UNAVAILABLE
        }
    }

    suspend fun suggestUsernames(name: String): List<String> = runCatching {
        val r = CloudFunctions.callJson("suggestUsernames", JsonObject(mapOf("name" to JsonPrimitive(name))))
        ((r["usernames"] as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .filter { UsernameRules.problem(it) == null }
    }.getOrDefault(emptyList())
```

(imports: `com.google.firebase.firestore.Source`, `kotlinx.serialization.json.booleanOrNull`.)

- [ ] **Step 2: `UsernameStep.kt`**: `UsernameCopy` (identical strings to iOS's `UsernameCopy`), `UsernameStatus` (sealed: Hint, Checking, Available(h), Taken(h), NotAvailable, Offline; `local(raw)` exactly as iOS), `UsernameField(text, onText, status, onStatus, suggestions, dark)` with `LaunchedEffect(text)` doing the same debounce (400 ms) and cloud check, `UsernameStep(vaultPath, dark, onDone)` (name first when `LedgerStore(vaultPath).loadSelf()?.name` is blank; suggestions with `LaunchedEffect(nameSource)` debounced; Save as `PrimaryAction`, enabled only on Available (and a name when asked); save = `setYourName` if asked → `CloudProfile.register(uid, store, AboutInfo.current(context).first)` → `People.setUsername(handle)` → `onDone()`; `ALREADY_EXISTS` → Taken, `INVALID_ARGUMENT` → NotAvailable, else Offline copy), `UsernameChangeScreen(current, vaultPath, dark, onSaved, onBack)` (the field, "Change username" primary enabled only on Available(h) with h != current, then an `AlertDialog` with `UsernameCopy.confirmTitle`/`confirmBody`, confirm "Change username", dismiss "Keep @current"), and `UsernameCard(vaultPath, dark, onPick)` (reads `People.accountUsername()` in a `LaunchedEffect`; draws the card only for `UsernameState.None`; nothing otherwise, and it adds no spacing because a Compose column skips an empty child). Write each composable as the iOS one in Task 4, Step 2, with `GroupScreenColumn`, `ScreenHeader`, `OutlinedTextField`, `PrimaryAction`, `SecondaryAction`, `Type`, `Tok`, `Modifier.glassCard(dark)` as `AddPersonScreen` uses them (People.kt:780-900 is the pattern to copy). **Known gap in this plan, stated:** the Android composables are not written out here; they are the line-by-line twin of Task 4 Step 2, written during execution, and the review of this task compares `UsernameStep.kt` with `UsernameStep.swift` side by side (copy, order, states, the debounce, the refusal mapping).

- [ ] **Step 3: Sign-in route (`AppRoot.kt` "signin" branch)**

Add `var usernameNeeded by remember { mutableStateOf<Boolean?>(null) }` beside `signedInAs`, and replace the `signedInAs?.let { … }` block with:

```kotlin
            signedInAs?.let { method ->
                LaunchedEffect(method) {
                    // C2: a second phone of the same account skips the step.
                    var state = People.accountUsername()
                    if (state == null) Account.uid()?.let { uid ->
                        CloudProfile.register(uid, LedgerStore(vaultPath), AboutInfo.current(context).first)
                        state = People.accountUsername()
                    }
                    usernameNeeded = state == UsernameState.None
                }
                when (usernameNeeded) {
                    true -> UsernameStep(vaultPath, dark) { usernameNeeded = false }
                    false -> SignedInConfirmation(method) {
                        signedInAs = null
                        usernameNeeded = null
                        route = back
                    }
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                return
            }
```

(`context` is `LocalContext.current` taken at the top of the composable, as other routes do; Back during the step does nothing: add `BackHandler(enabled = usernameNeeded == true) {}`.)

Add a route `"username"` that shows `UsernameStep(vaultPath, dark) { route = null }` for the card, and `"username/change"` that shows `UsernameChangeScreen(...)` for FindMeCard (FindMeCard gets `onPick` / `onChange` callbacks routed from its caller; keep `route` names in `HomeRoutes.parent` as the other `account/*` routes do, and add their parent entries).

- [ ] **Step 4: FindMeCard by state** (as iOS Step 4): `UsernameState` instead of `username`; "1 change left" + Change → `onChange(handle)`; Final → "Changed once, it stays now"; None → "Choose a username" → `onPick()`. Delete `UsernameEditor` and `PeopleCopy.REMOVE_USERNAME`.

- [ ] **Step 5: The cards**: `HomeGreeting` (Profile.kt) draws `UsernameCard(vaultPath, dark) { onPickUsername() }` under the greeting (thread the callback to `route = "username"`); the You screen under its hero likewise.

- [ ] **Step 6: Build, JVM tests, look** (`make android-core` is not needed: no core change). `zsh -lic 'cd android && ./gradlew :app:assemblePlayDebug :app:testPlayDebugUnitTest'`; install on `skipti-shots` (`-gpu host`), against the emulators (`--es args --emulators`): the same states as iOS Step 6, screenshots `.drafts/c2/`; check edge-to-edge on `Pixel_10_Pro_XL` (API 37) for the step's action at the bottom with the keyboard up. Shut every emulator down after.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/vardrlabs/skipti/UsernameStep.kt android/app/src/main/java/com/vardrlabs/skipti/People.kt android/app/src/main/java/com/vardrlabs/skipti/AppRoot.kt android/app/src/main/java/com/vardrlabs/skipti/Profile.kt <the You screen file>
git commit -m "C2: Android twin: username step, the one change with its confirm, the card [skip ci]"
```

### Task 6: Prove, record, deploy

- [ ] **Step 1: Sabotage** (`.drafts/c2/sabotage.py`, the D-208 runner's shape: asserted anchor, compiled, exactly the intended test red, restore, `cmp`):
  - S1 cloud: `usernameStep` lets a second change through (`if (changed) return { error … }` removed) → the setUsername emulator test only.
  - S2 cloud: `blocked` skips the `whole` list (`return WHOLE.some(…)` → `return false`) → people.test goldens only.
  - S3 cloud: `checkUsername` returns available for a taken name (the `owner.exists` check removed) → the checkUsername emulator test only.
  - S4 rules: users update allows `usernameChanged` (`changesOnly(['updatedAt', 'name'])` → add `'usernameChanged'`) → the new rules test only.
  - S5 iOS: `UsernameRules.blocked` skips `anywhere` → `UsernameRulesTests.testTheGoldens` only.
  - S6 Kotlin: same → `UsernameRulesTest.the_goldens` only.
  - S7 iOS/S8 Kotlin: `UsernameState.from` ignores `usernameChanged` → the state test only, each shell.
- [ ] **Step 2: Full gate** (machine free: no emulator, no simulator look in progress), in the background; nothing tracked edited meanwhile.
- [ ] **Step 3: Record** with `.drafts/common/record_unit.py` (row with 4 columns: …, files), §3 evidence, §4 residue (scunthorpe; the list is public; display names unchecked; Android/iOS looks against the emulators only until deploy), changelog; WORKLOG.
- [ ] **Step 4: Commit, merge `--no-ff`, push** (explicit paths, `git diff --cached --stat`, no attribution, `[skip ci]`; `gh run list` shows no new run).
- [ ] **Step 5: Deploy (the founder's yes in chat first):** read the three live usernames' verdicts first (`gcloud firestore` read-only, document IDs of `usernames/` only, with his yes) and run them through `people.username`; then `firebase deploy --only functions:setUsername,functions:checkUsername,functions:suggestUsernames --project skipti-26 --non-interactive` (pinned CLI, `zsh -lic`); read back each (ACTIVE, `skipti-functions`, 401 without a token); `scripts/cloud-audit.sh` (its expected exported count goes up by two: update the script's expectation in the same unit if it pins a number); CLOUD-SECURITY rows (two new public callables, `setUsername` changed); WORKLOG.
- [ ] **Step 6: The founder's check on phones** (a TestFlight/Play build is his call): new sign-in shows the step; a second phone skips it; the one change and its confirm; an old account sees the card.
