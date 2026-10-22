// Tests for firebase/functions (D-195): every refusal of the invite
// Functions, called the way the apps call them (the callable protocol over
// HTTP: POST {"data": …} with the caller's ID token), against the emulators
// (project demo-skipti). No dependency beyond Node's test runner and fetch.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

const PROJECT = 'demo-skipti';
const FS = `http://127.0.0.1:${process.env.FIRESTORE_PORT ?? 8180}`;
const FN = `http://127.0.0.1:${process.env.FUNCTIONS_PORT ?? 5001}/${PROJECT}/us-central1`;
const DOCS = `projects/${PROJECT}/databases/(default)/documents`;

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
function idToken(uid, claims = {}) {
  const now = Math.floor(Date.now() / 1000);
  return `${b64({ alg: 'none', typ: 'JWT' })}.${b64({
    iss: `https://securetoken.google.com/${PROJECT}`, aud: PROJECT, iat: now, exp: now + 3600,
    auth_time: now, sub: uid, user_id: uid, firebase: { sign_in_provider: 'google.com', identities: {} }, ...claims,
  })}.`;
}

// D-254: every account these tests act as is approved (approvals/{uid},
// seeded once a test, before its first call), so each refusal here is refused
// for the reason it names, never for the approval gate; a call the gate
// refuses anyway fails the test outright. The gate's own tests are in
// approvals.test.mjs.
let approvedUids = new Set();
async function approve(uid) {
  if (!uid || approvedUids.has(uid)) return;
  approvedUids.add(uid);
  await seed(`approvals/${uid}`, { state: 'approved' });
}
function notGated(name, uid, body) {
  if (body.error?.details?.approval) throw new Error(`${name}: ${uid} was refused by the approval gate (${body.error.details.approval})`);
}

/** Calls a Function; resolves to { result } or { error: status }. */
async function call(name, uid, data, claims) {
  await approve(uid);
  const r = await fetch(`${FN}/${name}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(uid && { authorization: `Bearer ${idToken(uid, claims)}` }) },
    body: JSON.stringify({ data }),
  });
  const body = await r.json();
  notGated(name, uid, body);
  return body.error ? { error: body.error.status } : { result: body.result };
}

const value = (v) => (Array.isArray(v) ? { arrayValue: { values: v.map(value) } }
  : typeof v === 'string' ? { stringValue: v } : v instanceof Date ? { timestampValue: v.toISOString() }
  : typeof v === 'boolean' ? { booleanValue: v }
  : v === null ? { nullValue: null }
  : v instanceof Uint8Array ? { bytesValue: Buffer.from(v).toString('base64') }
  : typeof v === 'object' ? { mapValue: { fields: Object.fromEntries(Object.entries(v).map(([k, x]) => [k, value(x)])) } }
  : { integerValue: String(v) });
async function seed(path, fields) {
  const r = await fetch(`${FS}/v1/${DOCS}/${path}`, {
    method: 'PATCH', headers: { 'content-type': 'application/json', authorization: 'Bearer owner' },
    body: JSON.stringify({ fields: Object.fromEntries(Object.entries(fields).map(([k, v]) => [k, value(v)])) }),
  });
  assert.equal(r.status, 200);
}
async function read(path) {
  const r = await fetch(`${FS}/v1/${DOCS}/${path}`, { headers: { authorization: 'Bearer owner' } });
  return r.status === 200 ? (await r.json()).fields : null;
}

const G = 'c'.repeat(32);
const M = 'd'.repeat(32);
const ask = (over = {}) => ({ groupId: G, memberId: M, memberName: 'Bo', groupName: 'Goa trip', inviterName: 'Al', ...over });

const D = 'f'.repeat(32); // a device id
beforeEach(async () => {
  const r = await fetch(`${FS}/emulator/v1/${DOCS}`, { method: 'DELETE' });
  assert.equal(r.status, 200, 'the Firestore emulator must be running');
  approvedUids = new Set(); // D-254: the wipe took the approvals too
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['al'], createdBy: 'al', createdAt: new Date() });
  // D-235: the group's log makes the place an invite names (a join needs it).
  await seed(`groups/${G}/records/1000-0-${D}`, {
    type: '3', hlc: { millis: 1000, counter: 0, device: D }, authorUid: 'al', deviceId: D, createdAt: new Date(),
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: new Uint8Array(Buffer.from(JSON.stringify({
      op: 'create', group: G, name: 'Goa trip', settlementCurrency: 'INR', members: [{ id: 'a'.repeat(32), name: 'Al' }, { id: M, name: 'Bo' }],
    }))) },
  });
});

test('a member invites a named person; the cloud keeps only the token\'s hash', async () => {
  const { result } = await call('createInvite', 'al', ask());
  assert.match(result.token, /^[A-Za-z0-9_-]{43}$/);
  const hash = createHash('sha256').update(result.token).digest('hex');
  const stored = await read(`invites/${hash}`);
  assert.equal(stored.groupId.stringValue, G);
  assert.equal(stored.memberId.stringValue, M);
  assert.equal(stored.createdBy.stringValue, 'al');
  assert.ok(!JSON.stringify(stored).includes(result.token), 'the token itself is never stored');
  const days = (result.expiresAt - Date.now()) / 86400000;
  assert.ok(days > 6.99 && days <= 7, `expires in ${days} days`);
});

test('only a signed-in member of the group can invite to it', async () => {
  assert.deepEqual(await call('createInvite', null, ask()), { error: 'UNAUTHENTICATED' });
  assert.deepEqual(await call('createInvite', 'eve', ask()), { error: 'PERMISSION_DENIED' });
  assert.deepEqual(await call('createInvite', 'al', ask({ groupId: 'e'.repeat(32) })), { error: 'PERMISSION_DENIED' });
});

test('an invite needs well-formed ids and names', async () => {
  for (const bad of [{ groupId: 'x' }, { memberId: 'nope' }, { memberName: '' }, { groupName: 'x'.repeat(81) }, { inviterName: 5 }]) {
    assert.deepEqual(await call('createInvite', 'al', ask(bad)), { error: 'INVALID_ARGUMENT' }, JSON.stringify(bad));
  }
});

test('peeking shows who, which group and as whom, and spends nothing', async () => {
  await seed('users/al', { name: 'Al' }); // D-235 decision 121: the inviter's name is their profile's
  const { result: { token } } = await call('createInvite', 'al', ask());
  const peek = await call('peekInvite', 'bo', { token });
  // D-230 decision 57: the group and the place too, so the phone builds its held place before it joins.
  assert.deepEqual({ ...peek.result, expiresAt: 0 },
    { groupName: 'Goa trip', memberName: 'Bo', inviterName: 'Al', expiresAt: 0, groupId: G, memberId: M });
  assert.deepEqual(await call('peekInvite', null, { token }), { error: 'UNAUTHENTICATED' });
  assert.ok((await call('acceptInvite', 'bo', { token })).result, 'still usable after a peek');
});

test('accepting adds the caller to the group once; the link then stops working', async () => {
  const { result: { token } } = await call('createInvite', 'al', ask());
  const accepted = await call('acceptInvite', 'bo', { token });
  assert.deepEqual(accepted.result, { groupId: G, memberId: M, groupName: 'Goa trip' });
  const group = await read(`groups/${G}`);
  assert.deepEqual(group.memberUids.arrayValue.values.map((v) => v.stringValue), ['al', 'bo']);
  assert.deepEqual(await call('acceptInvite', 'eve', { token }), { error: 'FAILED_PRECONDITION' });
  assert.deepEqual(await call('peekInvite', 'eve', { token }), { error: 'FAILED_PRECONDITION' });
  const after = await read(`groups/${G}`);
  assert.equal(after.memberUids.arrayValue.values.length, 2, 'eve is not in');
});

test('an expired, unknown or malformed invite admits nobody', async () => {
  const { result: { token } } = await call('createInvite', 'al', ask());
  const hash = createHash('sha256').update(token).digest('hex');
  await seed(`invites/${hash}`, { groupId: G, memberId: M, memberName: 'Bo', groupName: 'Goa trip', inviterName: 'Al',
    createdBy: 'al', createdAt: new Date(), expiresAt: new Date(Date.now() - 1000), usedBy: null });
  assert.deepEqual(await call('acceptInvite', 'bo', { token }), { error: 'FAILED_PRECONDITION' });
  assert.deepEqual(await call('acceptInvite', 'bo', { token: 'A'.repeat(43) }), { error: 'NOT_FOUND' });
  assert.deepEqual(await call('acceptInvite', 'bo', { token: 'short' }), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('acceptInvite', null, { token }), { error: 'UNAUTHENTICATED' });
  const group = await read(`groups/${G}`);
  assert.equal(group.memberUids.arrayValue.values.length, 1);
});

// ---- D-197: finding people ----
// sha256("priya@example.com"), derived with Python's hashlib.
const PRIYA = '6bdb7e961d54ddc243ec721e6e211ac0a179b19a9ce2ad52a418198e146e69fe';
const priyaEmail = { email: 'Priya@Example.com', email_verified: true };
async function list(path) {
  const r = await fetch(`${FS}/v1/${DOCS}/${path}`, { headers: { authorization: 'Bearer owner' } });
  return r.status === 200 ? (await r.json()).documents ?? [] : [];
}

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

test('a half profile (no participantId yet) gets no username', async () => {
  await seed('users/half', { findable: true });
  assert.deepEqual(await call('setUsername', 'half', { username: 'half1' }), { error: 'FAILED_PRECONDITION' });
  assert.equal(await read('usernames/half1'), null);
  assert.equal((await read('users/half')).username, undefined);
});

test('the step\'s first set refuses once a username exists, and spends no change', async () => {
  await profile('priya');
  await call('setUsername', 'priya', { username: 'priya' });
  assert.deepEqual(await call('setUsername', 'priya', { username: 'x_priya', first: true }), { error: 'FAILED_PRECONDITION' });
  assert.equal((await read('users/priya')).usernameChanged, undefined, 'the one change is not spent');
  assert.equal(await read('usernames/x_priya'), null);
  assert.deepEqual((await call('setUsername', 'priya', { username: 'priya', first: true })).result,
    { username: 'priya', canChange: true }, 'a retried first set is no change');
});

test('a change refused as taken does not spend the one change', async () => {
  await profile('priya'); await profile('eve');
  await call('setUsername', 'eve', { username: 'evex' });
  await call('setUsername', 'priya', { username: 'priya' });
  assert.deepEqual(await call('setUsername', 'priya', { username: 'evex' }), { error: 'ALREADY_EXISTS' });
  assert.equal((await read('users/priya')).usernameChanged, undefined);
  assert.deepEqual((await call('setUsername', 'priya', { username: 'priya2' })).result, { username: 'priya2', canChange: false });
});

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
  await seed('limits/al', { day: new Date().toISOString().slice(0, 10), searches: 0, hashes: 0, invites: 0, checks: 100 });
  assert.deepEqual(await call('suggestUsernames', 'al', { name: 'Al' }), { error: 'RESOURCE_EXHAUSTED' }, 'charged to the day\'s checks');
});

test('being findable keeps the verified email\'s fingerprint; off, or unverified, keeps none', async () => {
  await profile('priya');
  assert.deepEqual((await call('setFindable', 'priya', { findable: true }, priyaEmail)).result, { findable: true, byEmail: true });
  assert.equal((await read('users/priya')).emailHash.stringValue, PRIYA);
  assert.deepEqual((await call('setFindable', 'priya', { findable: false }, priyaEmail)).result, { findable: false, byEmail: false });
  const off = await read('users/priya');
  assert.equal(off.emailHash, undefined);
  assert.equal(off.findable.booleanValue, false);
  assert.deepEqual((await call('setFindable', 'priya', { findable: true }, { ...priyaEmail, email_verified: false })).result,
    { findable: true, byEmail: false });
  assert.equal((await read('users/priya')).emailHash, undefined);
  assert.deepEqual(await call('setFindable', 'priya', { findable: 'yes' }, priyaEmail), { error: 'INVALID_ARGUMENT' });
});

test('people are found by username or email fingerprint, only if findable, never yourself', async () => {
  await profile('priya');
  await call('setUsername', 'priya', { username: 'priya' });
  await call('setFindable', 'priya', { findable: true }, priyaEmail);
  await seed('users/priya', { name: 'Priya', username: 'priya', findable: true, emailHash: PRIYA });
  assert.deepEqual((await call('findPeople', 'al', { username: '@PRIYA' })).result.people,
    [{ uid: 'priya', name: 'Priya', username: 'priya', matched: null }]);
  assert.deepEqual((await call('findPeople', 'al', { emailHashes: [PRIYA, 'f'.repeat(64)] })).result.people,
    [{ uid: 'priya', name: 'Priya', username: 'priya', matched: PRIYA }]);
  assert.deepEqual((await call('findPeople', 'priya', { username: 'priya' })).result.people, [], 'not yourself');
  await call('setFindable', 'priya', { findable: false }, priyaEmail);
  assert.deepEqual((await call('findPeople', 'al', { username: 'priya' })).result.people, [], 'switched off');
  assert.deepEqual((await call('findPeople', 'al', { emailHashes: [PRIYA] })).result.people, []);
  assert.deepEqual(await call('findPeople', 'al', { emailHashes: ['priya@example.com'] }), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('findPeople', null, { username: 'priya' }), { error: 'UNAUTHENTICATED' });
});

test('searching stops at the day\'s limit', async () => {
  await seed('limits/al', { day: new Date().toISOString().slice(0, 10), searches: 40, hashes: 0, invites: 0 });
  assert.deepEqual(await call('findPeople', 'al', { username: 'priya' }), { error: 'RESOURCE_EXHAUSTED' });
});

test('a member invites someone found in the app: an invite in their account that joins once', async () => {
  await seed('users/priya', { name: 'Priya', findable: true });
  await seed('users/al', { name: 'Al' }); // D-235 decision 121: the inviter's name is their profile's
  assert.deepEqual((await call('inviteUser', 'al', { ...ask({ memberName: 'Priya' }), targetUid: 'priya' })).result, { invited: true });
  const docs = await list('users/priya/invites');
  assert.equal(docs.length, 1);
  const inv = docs[0].fields;
  assert.equal(inv.groupId.stringValue, G);
  assert.equal(inv.fromName.stringValue, 'Al');
  assert.equal(inv.memberName.stringValue, 'Priya');
  const token = inv.token.stringValue;
  const link = await read(`invites/${createHash('sha256').update(token).digest('hex')}`);
  assert.ok(link, 'the link invite exists, by hash');
  assert.equal(link.deliveredTo.stringValue, 'priya', 'and whose account holds its copy (deleteGroup ends it)');
  assert.deepEqual((await call('acceptInvite', 'priya', { token })).result, { groupId: G, memberId: M, groupName: 'Goa trip' });
  assert.deepEqual(await call('inviteUser', 'al', { ...ask(), targetUid: 'priya' }), { error: 'FAILED_PRECONDITION' },
    'already in the group');
});

// D-208: joining clears that invitation from your account, so none of your
// phones keeps a card whose Join can only fail ("already used"). Only YOUR
// copy of THAT invite goes: your other invitations stay, and a copy of the same
// token in someone else's account is not yours to touch.
test('joining from an in-app invite clears it from your invitations, and only it', async () => {
  await seed('users/priya', { name: 'Priya', findable: true });
  assert.deepEqual((await call('inviteUser', 'al', { ...ask({ memberName: 'Priya' }), targetUid: 'priya' })).result, { invited: true });
  const [mine] = await list('users/priya/invites');
  const token = mine.fields.token.stringValue;
  const other = { groupId: 'f'.repeat(32), groupName: 'Flat', memberId: M, memberName: 'Priya', token: 'B'.repeat(43) };
  await seed('users/priya/invites/other', other);
  await seed('users/bo/invites/copy', { ...other, token });
  assert.deepEqual((await call('acceptInvite', 'priya', { token })).result, { groupId: G, memberId: M, groupName: 'Goa trip' });
  const left = (await list('users/priya/invites')).map((d) => d.name.split('/').pop());
  assert.deepEqual(left, ['other'], 'the joined invite is gone; the other stays');
  assert.ok(await read('users/bo/invites/copy'), 'someone else\'s invitations are untouched');
});

test('only a member invites, only someone findable, never yourself', async () => {
  await seed('users/priya', { name: 'Priya', findable: true });
  await seed('users/hidden', { name: 'Hidden', findable: false });
  assert.deepEqual(await call('inviteUser', 'eve', { ...ask(), targetUid: 'priya' }), { error: 'PERMISSION_DENIED' });
  assert.deepEqual(await call('inviteUser', 'al', { ...ask(), targetUid: 'hidden' }), { error: 'NOT_FOUND' });
  assert.deepEqual(await call('inviteUser', 'al', { ...ask(), targetUid: 'nobody' }), { error: 'NOT_FOUND' });
  assert.deepEqual(await call('inviteUser', 'al', { ...ask(), targetUid: 'al' }), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('inviteUser', null, { ...ask(), targetUid: 'priya' }), { error: 'UNAUTHENTICATED' });
  assert.equal((await list('users/priya/invites')).length, 0);
});

// ---- D-198: your phones and deleting your account ----
// D-207: someone you already share a group with is invited by their
// participant id (the phone never holds their uid); being findable does not
// matter, sharing a group does.
const V = 'e'.repeat(32);
const PP = '1'.repeat(32);
const PZ = '2'.repeat(32);
const PA = '3'.repeat(32);
async function seedShared() {
  await seed(`groups/${V}`, { name: 'Flat', memberUids: ['al', 'priya'], createdBy: 'al', createdAt: new Date() });
  await seed('users/priya', { name: 'Priya', findable: false, participantId: PP });
  await seed('users/al', { name: 'Al', participantId: PA });
  await seed('users/zed', { name: 'Zed', participantId: PZ });
}

test('a member invites someone they share another group with, findable or not; the invite joins once', async () => {
  await seedShared();
  assert.deepEqual((await call('inviteKnown', 'al', { ...ask({ memberName: 'Priya' }), viaGroupId: V, participantId: PP })).result,
    { invited: true });
  const docs = await list('users/priya/invites');
  assert.equal(docs.length, 1);
  assert.equal(docs[0].fields.groupId.stringValue, G);
  const token = docs[0].fields.token.stringValue;
  assert.deepEqual((await call('acceptInvite', 'priya', { token })).result, { groupId: G, memberId: M, groupName: 'Goa trip' });
  assert.deepEqual(await call('inviteKnown', 'al', { ...ask(), viaGroupId: V, participantId: PP }), { error: 'FAILED_PRECONDITION' },
    'already in the group');
});

test('inviteKnown: only between people who share the group named, never yourself, never malformed', async () => {
  await seedShared();
  const go = (uid, over = {}) => call('inviteKnown', uid, { ...ask(), viaGroupId: V, participantId: PP, ...over });
  assert.deepEqual(await go('eve'), { error: 'PERMISSION_DENIED' }, 'the caller is in neither group');
  await seed(`groups/${'f'.repeat(32)}`, { name: 'Other', memberUids: ['priya'], createdBy: 'priya', createdAt: new Date() });
  assert.deepEqual(await go('al', { viaGroupId: 'f'.repeat(32) }), { error: 'PERMISSION_DENIED' }, 'the caller is not in the via group');
  assert.deepEqual(await go('al', { participantId: PZ }), { error: 'NOT_FOUND' }, 'the target is not in the via group');
  assert.deepEqual(await go('al', { participantId: '9'.repeat(32) }), { error: 'NOT_FOUND' }, 'nobody has that participant id');
  assert.deepEqual(await go('al', { participantId: PA }), { error: 'INVALID_ARGUMENT' }, 'yourself');
  assert.deepEqual(await go('al', { viaGroupId: 'nope' }), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await go('al', { participantId: 'nope' }), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await go(null), { error: 'UNAUTHENTICATED' });
  assert.equal((await list('users/priya/invites')).length, 0);
  assert.equal((await list('users/zed/invites')).length, 0);
});

const AUTH = `http://127.0.0.1:${process.env.AUTH_PORT ?? 9099}`;
const IDT = `${AUTH}/identitytoolkit.googleapis.com/v1/projects/${PROJECT}`;
async function authUser(uid) {
  const r = await fetch(`${IDT}/accounts:batchCreate`, {
    method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer owner' },
    body: JSON.stringify({ users: [{ localId: uid }] }),
  });
  assert.equal(r.status, 200);
}
async function validSince(uid) {
  const r = await fetch(`${IDT}/accounts:lookup`, {
    method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer owner' },
    body: JSON.stringify({ localId: [uid] }),
  });
  return Number((await r.json()).users?.[0]?.validSince ?? 0);
}
const P1 = 'a'.repeat(32);
const P2 = 'b'.repeat(32);
async function seedPhones() {
  await seed(`users/al/devices/${P1}`, { name: 'iPhone', platform: 'ios', fcmToken: 't1' });
  await seed(`users/al/devices/${P2}`, { name: 'Pixel 8', platform: 'android', fcmToken: 't2' });
}

test('you sign out one of your own phones; it is flagged with the time and loses its push address', async () => {
  await seedPhones();
  assert.deepEqual((await call('signOutDevice', 'al', { deviceId: P2 })).result, { signedOut: 1 });
  const phone = await read(`users/al/devices/${P2}`);
  assert.equal(phone.signedOut.booleanValue, true);
  assert.ok(phone.signedOutAt.timestampValue);
  assert.equal(phone.fcmToken, undefined);
  assert.equal((await read(`users/al/devices/${P1}`)).signedOut, undefined);
  assert.deepEqual(await call('signOutDevice', 'bo', { deviceId: P1 }), { error: 'NOT_FOUND' }, 'not someone else\'s');
  assert.equal((await read(`users/al/devices/${P1}`)).signedOut, undefined);
  assert.deepEqual(await call('signOutDevice', 'al', { deviceId: 'phone1' }), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('signOutDevice', null, { deviceId: P1 }), { error: 'UNAUTHENTICATED' });
});

test('signing out everywhere flags every phone and revokes the account\'s sessions', async () => {
  await authUser('al');
  await seedPhones();
  const before = await validSince('al');
  await new Promise((r) => setTimeout(r, 1100));
  assert.deepEqual((await call('signOutEverywhere', 'al', {})).result, { signedOut: 2 });
  assert.equal((await read(`users/al/devices/${P1}`)).signedOut.booleanValue, true);
  assert.equal((await read(`users/al/devices/${P2}`)).signedOut.booleanValue, true);
  assert.ok((await validSince('al')) > before, 'refresh tokens revoked');
  assert.deepEqual(await call('signOutEverywhere', null, {}), { error: 'UNAUTHENTICATED' });
});

test('asking to delete sets the date 7 days on and signs every phone out; signing in and keeping it cancels', async () => {
  await authUser('al');
  await seedPhones();
  await seed('users/al', { participantId: P1, name: 'Al' });
  const { result } = await call('requestDeletion', 'al', {});
  const days = (result.deleteAt - Date.now()) / 86400000;
  assert.ok(days > 6.99 && days <= 7, `${days} days`);
  const profile = await read('users/al');
  assert.equal(Date.parse(profile.deleteAt.timestampValue), result.deleteAt);
  assert.equal(profile.name.stringValue, 'Al', 'nothing deleted yet');
  assert.equal((await read(`users/al/devices/${P1}`)).signedOut.booleanValue, true);
  assert.deepEqual((await call('cancelDeletion', 'al', {})).result, { cancelled: true });
  assert.equal((await read('users/al')).deleteAt, undefined);
  assert.equal((await read('users/al')).name.stringValue, 'Al');
  assert.deepEqual(await call('requestDeletion', null, {}), { error: 'UNAUTHENTICATED' });
  assert.deepEqual(await call('cancelDeletion', null, {}), { error: 'UNAUTHENTICATED' });
});

// ---- D-235: the Opus pre-release audit's fixes (wave B) ----

test('D-235 L1: inviteUser\'s target is a Firebase uid, never a path into another account', async () => {
  await seed('users/priya', { name: 'Priya', findable: true });
  await seed(`users/priya/devices/${D}`, { name: 'Pixel 8', platform: 'android' });
  for (const targetUid of [`priya/devices/${D}`, 'priya/devices', 'pri ya', 'x'.repeat(129), '']) {
    assert.deepEqual(await call('inviteUser', 'al', { ...ask(), targetUid }), { error: 'INVALID_ARGUMENT' }, JSON.stringify(targetUid));
  }
  assert.equal(await read('limits/al'), null, 'no quota spent');
  const r = await fetch(`${FS}/v1/${DOCS}/users/priya/devices/${D}/invites`, { headers: { authorization: 'Bearer owner' } });
  assert.deepEqual((await r.json()).documents ?? [], [], 'nothing written under the phone');
  assert.deepEqual((await call('inviteUser', 'al', { ...ask(), targetUid: 'priya' })).result, { invited: true }, 'a uid still works');
});

// D-235 decision 121 (the founder; Firebase audit M2): an in-app invite names
// the inviter and the group from the server's records only: the inviter's
// profile name, and the group's name from its log (groupNameOf: the latest
// create or rename by HLC, here a rename the group document never saw). The
// phone's inviterName and groupName are never stored, shown or pushed: the
// push is people.invitation's, built from the same names as the copy
// (people.test.mjs).
const FORGED = { inviterName: 'SKIPTI Support', groupName: 'Your account is locked: skipti-help.example' };
async function renamed() {
  await seed(`groups/${G}/records/2000-0-${D}`, {
    type: '3', hlc: { millis: 2000, counter: 0, device: D }, authorUid: 'al', deviceId: D, createdAt: new Date(),
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: new Uint8Array(Buffer.from(JSON.stringify({
      op: 'renameGroup', group: G, name: 'Beach week',
    }))) },
  });
}
/** The one in-app invitation `uid` holds, and its link as peekInvite shows it. */
async function delivered(uid) {
  const docs = await list(`users/${uid}/invites`);
  assert.equal(docs.length, 1);
  const copy = docs[0].fields;
  const peek = (await call('peekInvite', uid, { token: copy.token.stringValue })).result;
  const stored = await read(`invites/${createHash('sha256').update(copy.token.stringValue).digest('hex')}`);
  return { copy, peek, stored };
}
function noForgery(...what) {
  for (const w of what) {
    for (const f of Object.values(FORGED)) assert.ok(!JSON.stringify(w).includes(f), `the phone's "${f}" is nowhere`);
  }
}

test('D-235 decision 121: inviteUser names the inviter and the group from the server, never from the phone', async () => {
  await seed('users/priya', { name: 'Priya', findable: true });
  await seed('users/al', { name: ' Al Server ' });
  await renamed();
  assert.deepEqual((await call('inviteUser', 'al', { ...ask(FORGED), targetUid: 'priya' })).result, { invited: true });
  const { copy, peek, stored } = await delivered('priya');
  assert.equal(copy.fromName.stringValue, 'Al Server', 'the copy names the inviter by their profile');
  assert.equal(copy.groupName.stringValue, 'Beach week', 'and the group by its log');
  assert.equal(peek.inviterName, 'Al Server');
  assert.equal(peek.groupName, 'Beach week');
  assert.equal(stored.inviterName.stringValue, 'Al Server');
  assert.equal(stored.groupName.stringValue, 'Beach week');
  noForgery(copy, peek, stored);
});

test('D-235 decision 121: inviteKnown names the inviter and the group from the server, never from the phone', async () => {
  await seedShared();
  await renamed();
  assert.deepEqual((await call('inviteKnown', 'al', { ...ask(FORGED), viaGroupId: V, participantId: PP })).result, { invited: true });
  const { copy, peek, stored } = await delivered('priya');
  assert.equal(copy.fromName.stringValue, 'Al');
  assert.equal(copy.groupName.stringValue, 'Beach week');
  assert.equal(peek.inviterName, 'Al');
  assert.equal(peek.groupName, 'Beach week');
  noForgery(copy, peek, stored);
});

test('D-235 decision 121: a nameless inviter is nameless everywhere (the apps\' "You are invited to", the push\'s "Someone")', async () => {
  await seed('users/priya', { name: 'Priya', findable: true });
  await seed('users/al', { name: '   ', participantId: '9'.repeat(32) });
  assert.deepEqual((await call('inviteUser', 'al', { ...ask(FORGED), targetUid: 'priya' })).result, { invited: true });
  const { copy, peek, stored } = await delivered('priya');
  assert.deepEqual(copy.fromName, { nullValue: null }, 'no name in the copy');
  assert.equal(copy.groupName.stringValue, 'Goa trip', 'the log\'s create names the group');
  assert.equal(peek.inviterName, null);
  noForgery(copy, peek, stored);
  // No profile at all: the same.
  await seed('users/bo', { name: 'Bo', findable: true });
  await fetch(`${FS}/v1/${DOCS}/users/al`, { method: 'DELETE', headers: { authorization: 'Bearer owner' } });
  assert.deepEqual((await call('inviteUser', 'al', { ...ask(FORGED), targetUid: 'bo' })).result, { invited: true });
  const bo = await delivered('bo');
  assert.deepEqual(bo.copy.fromName, { nullValue: null });
  assert.equal(bo.peek.inviterName, null);
});

test('D-235 decision 121: a link invite (createInvite) names the inviter and the group from the server; peekInvite shows them', async () => {
  await seed('users/al', { name: ' Al Server ' });
  await renamed();
  const { result: { token } } = await call('createInvite', 'al', ask(FORGED));
  const peek = (await call('peekInvite', 'bo', { token })).result;
  assert.equal(peek.inviterName, 'Al Server', 'the inviter by their profile');
  assert.equal(peek.groupName, 'Beach week', 'the group by its log');
  assert.equal(peek.memberName, 'Bo', 'the place keeps the name the group gave it');
  const stored = await read(`invites/${createHash('sha256').update(token).digest('hex')}`);
  noForgery(peek, stored);
  // A nameless inviter: no name on the link (the apps say "You are invited to").
  await seed('users/al', { name: '  ' });
  const again = (await call('createInvite', 'al', ask(FORGED))).result.token;
  const nameless = (await call('peekInvite', 'bo', { token: again })).result;
  assert.equal(nameless.inviterName, null);
  assert.equal(nameless.groupName, 'Beach week');
  noForgery(nameless);
});

test('D-235 L3: a Function never makes a profile; the phone\'s registration does, with its participant id', async () => {
  await authUser('newbie');
  assert.deepEqual(await call('setFindable', 'newbie', { findable: false }, priyaEmail), { error: 'FAILED_PRECONDITION' });
  // D-254: deleting an account with no profile no longer fails ("setting up"): the marker goes on its
  // approvals document instead (approvals.test.mjs), and still no half profile is made.
  assert.ok((await call('requestDeletion', 'newbie', {})).result.deleteAt);
  assert.ok((await read('approvals/newbie')).deleteAt, 'the marker is on the approval');
  assert.deepEqual((await call('cancelDeletion', 'newbie', {})).result, { cancelled: true });
  assert.equal((await read('approvals/newbie')).deleteAt, undefined, 'and kept');
  assert.equal(await read('users/newbie'), null, 'no half profile');
  await profile('newbie');
  assert.deepEqual((await call('setFindable', 'newbie', { findable: false }, priyaEmail)).result, { findable: false, byEmail: false });
  const made = await read('users/newbie');
  assert.equal(made.participantId.stringValue, '9'.repeat(32), 'the registration\'s participant id stays');
  assert.equal(made.findable.booleanValue, false);
  assert.ok((await call('requestDeletion', 'newbie', {})).result.deleteAt);
  assert.deepEqual((await call('cancelDeletion', 'newbie', {})).result, { cancelled: true });
  assert.equal((await read('users/newbie')).deleteAt, undefined);
});

// ---- D-257 (G-04): a group's daily invites and joins, on limits/group-{groupId} ----
const TODAY = () => new Date().toISOString().slice(0, 10);
const YESTERDAY = () => new Date(Date.now() - 86400000).toISOString().slice(0, 10);
const GL = `limits/group-${G}`;
const count = async (path, kind) => Number((await read(path))?.[kind]?.integerValue ?? 0);

test('D-257: each invite path charges the group once, a member\'s call only; a full day refuses all three, nothing written', async () => {
  await seedShared();
  await seed('users/priya', { name: 'Priya', findable: true, participantId: PP });
  assert.deepEqual(await call('createInvite', 'eve', ask()), { error: 'PERMISSION_DENIED' });
  assert.equal(await read(GL), null, 'a stranger\'s call charges no group');
  assert.ok((await call('createInvite', 'al', ask())).result);
  assert.equal(await count(GL, 'groupInvites'), 1, 'createInvite');
  assert.deepEqual((await call('inviteUser', 'al', { ...ask({ memberName: 'Priya' }), targetUid: 'priya' })).result, { invited: true });
  assert.equal(await count(GL, 'groupInvites'), 2, 'inviteUser');
  await seed(`groups/${V}`, { name: 'Flat', memberUids: ['al', 'zed'], createdBy: 'al', createdAt: new Date() });
  assert.deepEqual((await call('inviteKnown', 'al', { ...ask({ memberName: 'Zed' }), viaGroupId: V, participantId: PZ })).result,
    { invited: true });
  assert.equal(await count(GL, 'groupInvites'), 3, 'inviteKnown');
  assert.equal(await read(`limits/group-${V}`), null, 'the via group is not charged');
  // The day is full: all three refused before the log is read, nothing written, the account's own count untouched.
  await seed(GL, { day: TODAY(), groupInvites: 100 });
  const invitesBefore = (await list('invites')).length;
  const accountBefore = await read('limits/al');
  assert.deepEqual(await call('createInvite', 'al', ask()), { error: 'RESOURCE_EXHAUSTED' });
  assert.deepEqual(await call('inviteUser', 'al', { ...ask(), targetUid: 'priya' }), { error: 'RESOURCE_EXHAUSTED' });
  assert.deepEqual(await call('inviteKnown', 'al', { ...ask(), viaGroupId: V, participantId: PZ }), { error: 'RESOURCE_EXHAUSTED' });
  assert.equal((await list('invites')).length, invitesBefore, 'no invite made');
  assert.deepEqual(await read('limits/al'), accountBefore, 'the account\'s invites untouched');
  assert.equal(await count(GL, 'groupInvites'), 100);
  // The next UTC day starts again.
  await seed(GL, { day: YESTERDAY(), groupInvites: 100 });
  assert.ok((await call('createInvite', 'al', ask())).result);
  assert.equal(await count(GL, 'groupInvites'), 1);
});

test('D-257: a join is charged to the group with the join; a full day refuses it, the invite unspent; a refused join spends none', async () => {
  const { result: { token } } = await call('createInvite', 'al', ask());
  await seed(GL, { day: TODAY(), groupJoins: 50 });
  assert.deepEqual(await call('acceptInvite', 'bo', { token }), { error: 'RESOURCE_EXHAUSTED' });
  const hash = createHash('sha256').update(token).digest('hex');
  assert.equal((await read(`invites/${hash}`)).usedBy.nullValue, null, 'the invite is unspent');
  assert.deepEqual((await read(`groups/${G}`)).memberUids.arrayValue.values.map((v) => v.stringValue), ['al'], 'nobody joined');
  assert.equal(await count(GL, 'groupJoins'), 50);
  // A place the log never made: refused in the join's transaction, so the day's joins are not spent.
  await seed(GL, { day: TODAY(), groupJoins: 3 });
  const { result: { token: early } } = await call('createInvite', 'al', ask({ memberId: '7'.repeat(32) }));
  assert.deepEqual(await call('acceptInvite', 'cy', { token: early }), { error: 'FAILED_PRECONDITION' });
  assert.equal(await count(GL, 'groupJoins'), 3, 'a refused join spends none of the day');
  assert.deepEqual((await call('acceptInvite', 'bo', { token })).result, { groupId: G, memberId: M, groupName: 'Goa trip' });
  assert.equal(await count(GL, 'groupJoins'), 4, 'the join is charged');
});

// ---- D-257 (G-17, G-18): onGroupRecord's push, as the emulator stores it (FCM has none: emulatorPushes/{recordId}) ----
async function until(path, ok = () => true, ms = 20000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const d = await read(path);
    if (d && ok(d)) return d;
    await new Promise((r) => setTimeout(r, 150));
  }
  return null;
}
const pushed = (d) => d.notification.mapValue.fields;
async function seedPushGroup() {
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['al', 'bo'], createdBy: 'al', createdAt: new Date() });
  await seed('users/al', { name: '  Al  ' });
  await seed('users/bo', { name: 'Bo' });
  await seed(`users/bo/devices/${'a'.repeat(32)}`, { name: 'Pixel 8', platform: 'android', fcmToken: 'tb' });
}
const E = `1790000000123:7:${D}`;
async function noticed(id, uid, notice) {
  await seed(`groups/${G}/records/${id}`, {
    type: '1', hlc: { millis: 1790000000123, counter: 7, device: D }, authorUid: uid, deviceId: D, createdAt: new Date(),
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: new Uint8Array([2]) }, notice,
  });
}

test('D-257: the push names the author as the server holds them, never the line the phone wrote', async () => {
  await seedPushGroup();
  const a = `6001-0-${D}`;
  await noticed(a, 'al', { expense: E, kind: 'added', title: 'Dinner', amount: '₹1,200.00' });
  const first = await until(`emulatorPushes/${a}`);
  assert.ok(first, 'the push was built');
  assert.equal(pushed(first).title.stringValue, 'Goa trip');
  assert.equal(pushed(first).body.stringValue, 'Al added Dinner · ₹1,200.00');
  assert.equal(first.phones.integerValue, '1', 'bo\'s phone, never the author\'s');
  // A 0.5.0 phone's old line, written as another member's: its text is never sent.
  const b = `6002-0-${D}`;
  await noticed(b, 'al', { text: 'Bo added Refund · tap to confirm your card', expense: E });
  const old = await until(`emulatorPushes/${b}`);
  assert.equal(pushed(old).body.stringValue, 'Al changed an expense');
  assert.ok(!JSON.stringify(old).includes('card'), 'nothing of the phone\'s text');
  assert.equal(await count('limits/al', 'pushes'), 2, 'each push charged to its author');
});

test('D-257: the 301st push of an author\'s day is held back: the record stays, nothing is sent', async () => {
  await seedPushGroup();
  await seed('limits/al', { day: TODAY(), pushes: 299, searches: 3 });
  const last = `7001-0-${D}`;
  await noticed(last, 'al', { expense: E, kind: 'changed', title: 'Taxi' });
  assert.ok(await until(`emulatorPushes/${last}`), 'the 300th goes');
  assert.equal(await count('limits/al', 'pushes'), 300);
  const over = `7002-0-${D}`;
  await noticed(over, 'al', { expense: E, kind: 'deleted', title: 'an expense' });
  const capped = await until('limits/al', (d) => d.pushesCapped?.booleanValue === true);
  assert.ok(capped, 'the day is marked capped');
  assert.equal(await read(`emulatorPushes/${over}`), null, 'the 301st sends nothing');
  assert.ok(await read(`groups/${G}/records/${over}`), 'the record stays');
  assert.equal(await count('limits/al', 'pushes'), 300);
  assert.equal(await count('limits/al', 'searches'), 3, 'the account\'s other counters untouched');
});
