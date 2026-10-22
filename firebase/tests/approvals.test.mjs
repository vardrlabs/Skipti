// D-254 (decisions 228, 229): the approval gate, against the emulators
// (project demo-skipti). Anyone can sign in; until the founder approves the
// account, the rules refuse every client write of it and every Function but
// the exempt ones refuses it. The decisions (firebase/functions/approvals.js)
// are tested directly; the rules through the emulators' REST endpoints; the
// Functions the way the phones and the admin page call them (the callable
// protocol over HTTP); the purge with the admin SDK, as account.test.mjs does.
// The emulators' admin uid is functions/.env.local's `emulator-admin`.
import { test, before, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import * as approvals from '../functions/approvals.js';
import { approveFromBeforeGate, purgeAccount, purgeDue } from '../functions/account-store.js';
import { initializeApp } from '../functions/node_modules/firebase-admin/lib/esm/app/index.js';
import { getFirestore, Timestamp } from '../functions/node_modules/firebase-admin/lib/esm/firestore/index.js';
import { getAuth } from '../functions/node_modules/firebase-admin/lib/esm/auth/index.js';

const PROJECT = 'demo-skipti';
const FS = `http://127.0.0.1:${process.env.FIRESTORE_PORT ?? 8180}`;
const FN = `http://127.0.0.1:${process.env.FUNCTIONS_PORT ?? 5001}/${PROJECT}/us-central1`;
const ST = `http://127.0.0.1:${process.env.STORAGE_PORT ?? 9199}`;
const AUTH = `http://127.0.0.1:${process.env.AUTH_PORT ?? 9099}`;
const IDT = `${AUTH}/identitytoolkit.googleapis.com/v1/projects/${PROJECT}`;
const BUCKET = `${PROJECT}.appspot.com`;
const DOCS = `projects/${PROJECT}/databases/(default)/documents`;
const ADMIN = 'emulator-admin';

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
function idToken(uid, claims = {}) {
  const now = Math.floor(Date.now() / 1000);
  return `${b64({ alg: 'none', typ: 'JWT' })}.${b64({
    iss: `https://securetoken.google.com/${PROJECT}`, aud: PROJECT, iat: now, exp: now + 3600,
    auth_time: now, sub: uid, user_id: uid, firebase: { sign_in_provider: 'google.com', identities: {} }, ...claims,
  })}.`;
}
const APPLE = { firebase: { sign_in_provider: 'apple.com', identities: {} } };

/** Calls a Function: { result } or { error, message, details }. */
async function call(name, uid, data, claims) {
  const r = await fetch(`${FN}/${name}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(uid && { authorization: `Bearer ${idToken(uid, claims)}` }) },
    body: JSON.stringify({ data }),
  });
  const body = await r.json();
  return body.error ? { error: body.error.status, message: body.error.message, details: body.error.details ?? null }
    : { result: body.result };
}

// ---- Firestore and Storage through the rules (rules.test.mjs's helpers) ----
const SERVER_TIME = Symbol('serverTime');
const DROP = Symbol('drop'); // in an 'update': the field is in the mask and absent, so it is removed
function value(v) {
  if (v === null) return { nullValue: null };
  if (typeof v === 'string') return { stringValue: v };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (typeof v === 'number') return { integerValue: String(v) };
  if (v instanceof Date) return { timestampValue: v.toISOString() };
  if (v instanceof Uint8Array) return { bytesValue: Buffer.from(v).toString('base64') };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(value) } };
  return { mapValue: { fields: fields(v) } };
}
function fields(o) {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== SERVER_TIME && v !== DROP).map(([k, v]) => [k, value(v)]));
}
const auth = (uid, claims) => (uid === 'owner' ? 'Bearer owner' : uid ? `Bearer ${idToken(uid, claims)}` : undefined);
async function write(uid, path, data, mode = 'create', claims = {}) {
  const name = `${DOCS}/${path}`;
  let w;
  if (mode === 'delete') {
    w = { delete: name };
  } else {
    const plain = Object.fromEntries(Object.entries(data).filter(([, v]) => v !== SERVER_TIME));
    const kept = Object.fromEntries(Object.entries(plain).filter(([, v]) => v !== DROP));
    w = { update: { name, fields: fields(kept) } };
    const stamps = Object.entries(data).filter(([, v]) => v === SERVER_TIME).map(([k]) => k);
    if (stamps.length) w.updateTransforms = stamps.map((f) => ({ fieldPath: f, setToServerValue: 'REQUEST_TIME' }));
    if (mode === 'create') w.currentDocument = { exists: false };
    if (mode === 'update') {
      w.currentDocument = { exists: true };
      w.updateMask = { fieldPaths: Object.keys(plain) };
    }
  }
  const a = auth(uid, claims);
  const r = await fetch(`${FS}/v1/${DOCS.split('/documents')[0]}/documents:commit`, {
    method: 'POST', headers: { 'content-type': 'application/json', ...(a && { authorization: a }) },
    body: JSON.stringify({ writes: [w] }),
  });
  return r.status;
}
async function get(uid, path) {
  const a = auth(uid);
  return (await fetch(`${FS}/v1/${DOCS}/${path}`, { headers: a ? { authorization: a } : {} })).status;
}
async function list(uid, parent) {
  const [collectionId] = parent.split('/').slice(-1);
  const prefix = parent.split('/').slice(0, -1).join('/');
  const r = await fetch(`${FS}/v1/${DOCS}${prefix ? '/' + prefix : ''}:runQuery`, {
    method: 'POST', headers: { 'content-type': 'application/json', ...(auth(uid) && { authorization: auth(uid) }) },
    body: JSON.stringify({ structuredQuery: { from: [{ collectionId }] } }),
  });
  return r.status;
}
const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
async function upload(uid, path) {
  const r = await fetch(`${ST}/v0/b/${BUCKET}/o?name=${encodeURIComponent(path)}`, {
    method: 'POST', headers: { 'content-type': 'image/jpeg', authorization: `Firebase ${idToken(uid)}` }, body: jpeg,
  });
  await r.arrayBuffer();
  return r.status;
}
async function download(uid, path) {
  const r = await fetch(`${ST}/v0/b/${BUCKET}/o/${encodeURIComponent(path)}?alt=media`, {
    headers: { authorization: `Firebase ${idToken(uid)}` },
  });
  await r.arrayBuffer();
  return r.status;
}
async function removeFile(uid, path) {
  const r = await fetch(`${ST}/v0/b/${BUCKET}/o/${encodeURIComponent(path)}`, {
    method: 'DELETE', headers: { authorization: `Firebase ${idToken(uid)}` },
  });
  await r.arrayBuffer();
  return r.status;
}

const OK = 200;
const DENIED = 403;
const P = 'a'.repeat(32); // a participant id
const D = 'b'.repeat(32); // a device id
const record = (author) => ({
  type: '1', hlc: { millis: 1790000000123, counter: 0, device: D }, authorUid: author, deviceId: D,
  createdAt: SERVER_TIME,
  payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1, 2, 3, 4]),
    blob: new Uint8Array(Buffer.from('{"amountMinor":120000,"currency":"INR"}')) },
});
const profile = () => ({ participantId: P, createdAt: SERVER_TIME, updatedAt: SERVER_TIME });
const device = () => ({ name: 'Pixel 8', platform: 'android', appVersion: '0.5.1', lastSeen: SERVER_TIME });
const group = (uid) => ({ name: 'Goa trip', memberUids: [uid], createdBy: uid, createdAt: SERVER_TIME });

let db;
let adminAuth;
before(() => {
  const app = initializeApp({ projectId: PROJECT }, 'approvals-test');
  db = getFirestore(app);
  adminAuth = getAuth(app);
});
beforeEach(async () => {
  assert.equal((await fetch(`${FS}/emulator/v1/${DOCS}`, { method: 'DELETE' })).status, OK, 'the Firestore emulator must be running');
  await fetch(`${AUTH}/emulator/v1/projects/${PROJECT}/accounts`, { method: 'DELETE' });
});
async function authUser(uid) {
  const r = await fetch(`${IDT}/accounts:batchCreate`, {
    method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer owner' },
    body: JSON.stringify({ users: [{ localId: uid }] }),
  });
  assert.equal(r.status, 200);
}
/** Sets `uid`'s approval: 'none' (no document), pending, approved or denied. */
async function setState(uid, state, extra = {}) {
  if (state === 'none') return db.doc(`approvals/${uid}`).delete();
  return db.doc(`approvals/${uid}`).set({ state, requestedAt: Timestamp.fromMillis(Date.now() - 60000), ...extra });
}
const stored = async (path) => (await db.doc(path).get()).data() ?? null;
const UNAPPROVED = ['none', 'pending', 'denied'];
const WORDS = { none: 'This account is waiting for approval.', pending: 'This account is waiting for approval.', denied: 'This account was not approved.' };

// ---- the decisions (approvals.js) ----

test('the gate refuses every state but approved, in words people read, with the state in details', () => {
  assert.equal(approvals.stateOf(null), 'none');
  assert.equal(approvals.stateOf({ deleteAt: 1 }), 'none', 'a deletion marker alone asked for nothing');
  assert.equal(approvals.stateOf({ state: 'bogus' }), 'none');
  for (const s of approvals.STATES) assert.equal(approvals.stateOf({ state: s }), s);
  assert.equal(approvals.refusal('approved'), null);
  for (const s of UNAPPROVED) {
    assert.deepEqual(approvals.refusal(s), { message: WORDS[s], details: { approval: s } });
    assert.doesNotMatch(approvals.refusal(s).message, /[\u2013\u2014]/, 'no em or en dash in what people read');
  }
  assert.doesNotMatch(approvals.NOT_ADMIN, /[\u2013\u2014]/);
  assert.deepEqual(approvals.EXEMPT.slice().sort(),
    ['adminApprovals', 'cancelDeletion', 'requestApproval', 'requestDeletion', 'signOutDevice', 'signOutEverywhere']);
});

test('requestApproval\'s input: a platform, a short trimmed name or none, the provider, and only a verified email', () => {
  const google = { firebase: { sign_in_provider: 'google.com' }, email: 'al@example.com', email_verified: true };
  assert.deepEqual(approvals.requestFields({ name: '  Al  ', platform: 'ios' }, google),
    { name: 'Al', platform: 'ios', provider: 'google.com', email: 'al@example.com' });
  assert.deepEqual(approvals.requestFields({ platform: 'android' }, { ...google, email_verified: false }),
    { name: null, platform: 'android', provider: 'google.com', email: null });
  assert.equal(approvals.requestFields({ name: '   ', platform: 'ios' }, google).name, null, 'blank is no name');
  assert.equal(approvals.requestFields({ name: 'x'.repeat(60), platform: 'ios' }, google).name.length, 60);
  assert.equal(approvals.requestFields({ platform: 'ios' }, { firebase: { sign_in_provider: 'apple.com' } }).provider, 'apple.com');
  for (const bad of [{}, { platform: 'web' }, { platform: 'ios', name: 'x'.repeat(61) }, { platform: 'ios', name: 5 }, null]) {
    assert.ok(approvals.requestFields(bad, google).error, JSON.stringify(bad));
  }
});

test('requestApproval\'s write: a new request, a refresh of a pending one, and never a change to a decision', () => {
  const f = { name: 'Al', platform: 'ios', provider: 'google.com', email: 'al@example.com' };
  assert.deepEqual(approvals.requestWrite(null, f, 'NOW', 'DROP'),
    { state: 'pending', requestedAt: 'NOW', platform: 'ios', provider: 'google.com', name: 'Al', email: 'al@example.com' });
  assert.deepEqual(approvals.requestWrite({ deleteAt: 1 }, { ...f, name: null, email: null }, 'NOW', 'DROP'),
    { state: 'pending', requestedAt: 'NOW', platform: 'ios', provider: 'google.com' });
  assert.deepEqual(approvals.requestWrite({ state: 'pending' }, { ...f, name: null }, 'NOW', 'DROP'),
    { platform: 'ios', name: 'DROP', email: 'al@example.com' }, 'refreshed, never requestedAt');
  assert.equal(approvals.requestWrite({ state: 'approved' }, f, 'NOW', 'DROP'), null);
  assert.equal(approvals.requestWrite({ state: 'denied' }, f, 'NOW', 'DROP'), null);
});

test('the admin is the configured uid signed in with Google; an empty or unset param admits nobody', () => {
  assert.equal(approvals.isAdmin('ad', 'google.com', 'ad'), true);
  assert.equal(approvals.isAdmin('ad', 'google.com', ' ad '), true, 'a param pasted with spaces');
  assert.equal(approvals.isAdmin('ad', 'apple.com', 'ad'), false, 'the same uid through Apple');
  assert.equal(approvals.isAdmin('ad', undefined, 'ad'), false);
  assert.equal(approvals.isAdmin('eve', 'google.com', 'ad'), false);
  for (const empty of ['', '   ', undefined, null]) {
    assert.equal(approvals.isAdmin('ad', 'google.com', empty), false, JSON.stringify(empty));
    assert.equal(approvals.isAdmin('', 'google.com', empty), false, `an empty uid with ${JSON.stringify(empty)}`);
  }
});

test('the migration approves profiles with no decision; rows and order', () => {
  assert.equal(approvals.migrates(null), true);
  assert.equal(approvals.migrates({ state: 'pending' }), true);
  assert.equal(approvals.migrates({ deleteAt: 1 }), true);
  assert.equal(approvals.migrates({ state: 'approved' }), false);
  assert.equal(approvals.migrates({ state: 'denied' }), false);
  const t = (ms) => ({ toMillis: () => ms });
  assert.deepEqual(approvals.row('u1', { state: 'approved', requestedAt: t(5), decidedAt: t(9), decidedBy: 'admin', name: 'Al', platform: 'ios',
    provider: 'google.com', email: 'al@example.com', deleteAt: t(1) }),
  { uid: 'u1', state: 'approved', requestedAt: 5, decidedAt: 9, decidedBy: 'admin', name: 'Al', platform: 'ios', provider: 'google.com', email: 'al@example.com' });
  assert.deepEqual(approvals.row('u2', { state: 'pending', requestedAt: t(7) }), { uid: 'u2', state: 'pending', requestedAt: 7 });
  assert.deepEqual(approvals.newestFirst([{ uid: 'a', requestedAt: 1 }, { uid: 'b', requestedAt: 3 }, { uid: 'c', requestedAt: 2 }], 2).map((r) => r.uid), ['b', 'c']);
  assert.deepEqual(approvals.decision({ uid: 'u1', state: 'denied' }, /^[a-z0-9]+$/), { uid: 'u1', state: 'denied' });
  assert.ok(approvals.decision({ uid: 'u1', state: 'pending' }, /^[a-z0-9]+$/).error);
  assert.ok(approvals.decision({ uid: 'a/b', state: 'approved' }, /^[a-z0-9]+$/).error);
});

// ---- the rules ----

/** Every client write the gate covers, in an order that needs nothing but
 *  `uid` being a member of g1 (seeded as the owner between steps). Returns
 *  [label, status] for each gated write. */
async function gatedWrites(uid) {
  const out = [];
  out.push(['profile create', await write(uid, `users/${uid}`, profile())]);
  await write('owner', `users/${uid}`, { participantId: P, createdAt: new Date(), updatedAt: new Date() }, 'set');
  out.push(['profile update', await write(uid, `users/${uid}`, { updatedAt: SERVER_TIME, name: 'Al' }, 'update')]);
  out.push(['device create', await write(uid, `users/${uid}/devices/${D}`, device())]);
  await write('owner', `users/${uid}/devices/${D}`, { name: 'Pixel 8', platform: 'android', appVersion: '0.5.0', lastSeen: new Date() }, 'set');
  out.push(['device update', await write(uid, `users/${uid}/devices/${D}`, { name: 'Pixel 9', appVersion: '0.5.1', lastSeen: SERVER_TIME }, 'update')]);
  out.push(['personal record create', await write(uid, `users/${uid}/records/r1`, record(uid))]);
  out.push(['group create', await write(uid, 'groups/g2', group(uid))]);
  await write('owner', 'groups/g1', { name: 'Goa trip', memberUids: [uid, 'bo'], createdBy: 'bo', createdAt: new Date() }, 'set');
  out.push(['group update', await write(uid, 'groups/g1', { name: 'Goa 2026' }, 'update')]);
  out.push(['group record create', await write(uid, 'groups/g1/records/r1', record(uid))]);
  out.push(['group picture write', await upload(uid, 'groups/g1/picture.jpg')]);
  return out;
}

for (const state of UNAPPROVED) {
  test(`D-254 rules: every client write of an account with approval ${state} is refused (Firestore and Storage)`, async () => {
    await setState('al', state);
    const results = await gatedWrites('al');
    assert.equal(results.length, 9);
    for (const [label, status] of results) assert.equal(status, DENIED, `${label} (${state})`);
  });
}

test('D-254 rules: an approval holding only a deletion marker is no approval (every gated write refused)', async () => {
  await db.doc('approvals/al').set({ deleteAt: Timestamp.fromMillis(Date.now() + 86400000), deletionRequestedAt: Timestamp.fromMillis(Date.now()) });
  const results = await gatedWrites('al');
  assert.equal(results.length, 9);
  for (const [label, status] of results) assert.equal(status, DENIED, `${label} (marker only)`);
});

// Fix wave M3: removing the push address never waits (a denied account clears
// its token, as both shells' tokenUpdate(nil) writes it); adding or changing
// one, or anything else beside the removal, still needs approval.
test('D-254 rules: an unapproved account removes its push token, and nothing more', async () => {
  for (const state of UNAPPROVED) {
    await setState('al', state);
    const path = `users/al/devices/${D}`;
    const seed = () => write('owner', path, { name: 'Pixel 8', platform: 'android', appVersion: '0.5.0', lastSeen: new Date(), fcmToken: 't1' }, 'set');
    await seed();
    assert.equal(await write('al', path, { fcmToken: DROP, lastSeen: SERVER_TIME }, 'update'), OK, `${state}: the token is removed`);
    assert.equal((await stored(path)).fcmToken, undefined, `${state}: gone`);
    assert.equal(await write('al', path, { fcmToken: 't2', lastSeen: SERVER_TIME }, 'update'), DENIED, `${state}: adding one waits`);
    await seed();
    assert.equal(await write('al', path, { fcmToken: 't2', lastSeen: SERVER_TIME }, 'update'), DENIED, `${state}: changing one waits`);
    assert.equal(await write('al', path, { fcmToken: DROP, lastSeen: SERVER_TIME, name: 'Pixel 9' }, 'update'), DENIED, `${state}: a name beside it waits`);
    assert.equal(await write('al', path, { fcmToken: DROP, lastSeen: SERVER_TIME, appVersion: '0.5.1' }, 'update'), DENIED, `${state}: a version beside it waits`);
    assert.equal(await write('al', path, { fcmToken: DROP, lastSeen: new Date(Date.now() - 60000) }, 'update'), DENIED, `${state}: lastSeen is the server's time`);
    assert.equal((await stored(path)).fcmToken, 't1', `${state}: unchanged by the refusals`);
    assert.equal(await write('bo', path, { fcmToken: DROP, lastSeen: SERVER_TIME }, 'update'), DENIED, `${state}: only the account itself`);
  }
});

test('D-254 rules: the same writes of an approved account are allowed', async () => {
  await setState('al', 'approved');
  for (const [label, status] of await gatedWrites('al')) assert.equal(status, OK, label);
});

test('D-254 rules: a decision takes effect on the next write, both ways', async () => {
  await setState('al', 'approved');
  assert.equal(await write('al', 'groups/g1', group('al')), OK);
  await setState('al', 'denied');
  assert.equal(await write('al', 'groups/g1', { name: 'Denied now' }, 'update'), DENIED);
  await setState('al', 'approved');
  assert.equal(await write('al', 'groups/g1', { name: 'Approved again' }, 'update'), OK);
});

test('D-254 rules: reading and deleting what is yours never wait for approval', async () => {
  await setState('al', 'pending');
  await setState('bo', 'approved');
  await write('owner', 'users/al', { participantId: P, createdAt: new Date(), updatedAt: new Date() }, 'set');
  await write('owner', `users/al/devices/${D}`, { name: 'Pixel 8', platform: 'android', appVersion: '0.5.0', lastSeen: new Date() }, 'set');
  await write('owner', 'users/al/records/r1', { type: '1', authorUid: 'al' }, 'set');
  await write('owner', 'users/al/invites/i1', { groupId: 'g1' }, 'set');
  await write('owner', 'users/al/notices/n1', { kind: 'removed' }, 'set');
  await write('owner', 'groups/g1', { name: 'Goa trip', memberUids: ['al', 'bo'], createdBy: 'bo', createdAt: new Date() }, 'set');
  await write('owner', 'groups/g1/records/r1', { type: '1', authorUid: 'bo', createdAt: new Date() }, 'set');
  assert.equal(await upload('bo', 'groups/g1/picture.jpg'), OK, 'an approved member stores the picture');
  assert.equal(await get('al', 'users/al'), OK);
  assert.equal(await list('al', 'users/al/devices'), OK);
  assert.equal(await get('al', 'users/al/records/r1'), OK);
  assert.equal(await list('al', 'users/al/records'), OK);
  assert.equal(await get('al', 'groups/g1'), OK);
  assert.equal(await get('al', 'groups/g1/records/r1'), OK);
  assert.equal(await list('al', 'groups/g1/records'), OK);
  assert.equal(await download('al', 'groups/g1/picture.jpg'), OK);
  assert.equal(await write('al', 'users/al/invites/i1', null, 'delete'), OK);
  assert.equal(await write('al', 'users/al/notices/n1', null, 'delete'), OK);
  assert.equal(await write('al', `users/al/devices/${D}`, null, 'delete'), OK);
  assert.ok([200, 204].includes(await removeFile('al', 'groups/g1/picture.jpg')), 'a member deletes the picture');
});

test('D-254 rules: an account reads its own approval; nobody else does, nobody lists them, no client writes one', async () => {
  await setState('al', 'pending');
  await setState(ADMIN, 'approved');
  assert.equal(await get('al', 'approvals/al'), OK);
  assert.equal(await get('al', 'approvals/nobody-yet'), DENIED, 'not even a missing one of another');
  assert.equal(await get('al', `approvals/${ADMIN}`), DENIED);
  assert.equal(await get('bo', 'approvals/al'), DENIED);
  assert.equal(await get(null, 'approvals/al'), DENIED);
  assert.equal(await get(ADMIN, 'approvals/al'), DENIED, 'the admin reads them through adminApprovals only');
  assert.equal(await list('al', 'approvals'), DENIED);
  assert.equal(await list(ADMIN, 'approvals'), DENIED);
  for (const uid of ['al', 'bo', ADMIN]) {
    assert.equal(await write(uid, `approvals/${uid === 'al' ? 'al' : 'bo'}`, { state: 'approved' }, 'set'), DENIED, `${uid} sets`);
    assert.equal(await write(uid, 'approvals/al', { state: 'approved' }, 'update'), DENIED, `${uid} updates`);
    assert.equal(await write(uid, 'approvals/al', null, 'delete'), DENIED, `${uid} deletes`);
    assert.equal(await write(uid, `approvals/new-${uid}`, { state: 'approved' }), DENIED, `${uid} creates`);
  }
  assert.equal((await stored('approvals/al')).state, 'pending', 'unchanged');
});

test('D-254 rules: meta (the migration marker) has no client access, the admin\'s included', async () => {
  await setState(ADMIN, 'approved');
  await db.doc('meta/approvalsMigration').set({ count: 1 });
  for (const uid of ['al', ADMIN]) {
    assert.equal(await get(uid, 'meta/approvalsMigration'), DENIED);
    assert.equal(await list(uid, 'meta'), DENIED);
    assert.equal(await write(uid, 'meta/approvalsMigration', { count: 0 }, 'set'), DENIED);
    assert.equal(await write(uid, 'meta/approvalsMigration', null, 'delete'), DENIED);
    assert.equal(await write(uid, 'meta/other', { x: 1 }), DENIED);
  }
});

// ---- the Functions ----

const source = readFileSync(new URL('../functions/index.js', import.meta.url), 'utf8');
const callables = [...source.matchAll(/^export const (\w+) = onCall\(/gm)].map((m) => m[1]);

test('D-254: index.js\'s callables are found, and every exempt one exists', () => {
  assert.ok(callables.length >= 25, `${callables.length} callables`);
  for (const name of approvals.EXEMPT) assert.ok(callables.includes(name), `${name} is a callable`);
  // Every gated callable's first statement is the gate, before any argument is read.
  for (const name of callables.filter((n) => !approvals.EXEMPT.includes(n))) {
    const at = source.indexOf(`export const ${name} = onCall(`);
    const head = source.slice(at, source.indexOf('\n', source.indexOf('\n', at) + 1));
    assert.match(head, /=> \{\n {2}(const uid = )?await approvedCaller\(request\);$/, `${name} starts with approvedCaller`);
  }
});

for (const state of UNAPPROVED) {
  test(`D-254: every callable but the exempt ones refuses a caller with approval ${state}, first, with details.approval`, async () => {
    const uid = `caller-${state}`;
    await setState(uid, state);
    const gated = callables.filter((n) => !approvals.EXEMPT.includes(n));
    assert.ok(gated.length >= 20, `${gated.length} gated callables`);
    for (const name of gated) {
      assert.deepEqual(await call(name, uid, {}), { error: 'PERMISSION_DENIED', message: WORDS[state], details: { approval: state } }, name);
    }
    // Signed in comes first: no token is still UNAUTHENTICATED.
    for (const name of gated) assert.equal((await call(name, null, {})).error, 'UNAUTHENTICATED', name);
  });
}

test('D-254: the exempt callables are never refused for approval', async () => {
  for (const state of UNAPPROVED) {
    const uid = `exempt-${state}`;
    await authUser(uid);
    await setState(uid, state);
    for (const name of approvals.EXEMPT) {
      const r = await call(name, uid, {});
      assert.ok(!r.details?.approval, `${name} (${state}): ${JSON.stringify(r)}`);
    }
    assert.ok((await call('signOutEverywhere', uid, {})).result, `${state}: sign out everywhere works`);
    assert.ok((await call('requestDeletion', uid, {})).result.deleteAt, `${state}: deleting works`);
    assert.deepEqual((await call('cancelDeletion', uid, {})).result, { cancelled: true }, `${state}: keeping works`);
  }
});

test('D-254: an approved caller passes the gate (a gated callable runs)', async () => {
  await setState('al', 'approved');
  const r = await call('suggestUsernames', 'al', { name: 'Al Ray' });
  assert.ok(Array.isArray(r.result?.usernames), JSON.stringify(r));
  await setState('al', 'denied');
  assert.equal((await call('suggestUsernames', 'al', { name: 'Al Ray' })).details?.approval, 'denied', 'taken away: refused at once');
});

test('D-254 requestApproval: creates a pending request with the name, platform, provider and a verified email only', async () => {
  const r = await call('requestApproval', 'al', { name: '  Al Ray ', platform: 'ios' }, { email: 'al@example.com', email_verified: true });
  assert.deepEqual(r.result, { state: 'pending' });
  const a = await stored('approvals/al');
  assert.equal(a.state, 'pending');
  assert.equal(a.name, 'Al Ray');
  assert.equal(a.platform, 'ios');
  assert.equal(a.provider, 'google.com');
  assert.equal(a.email, 'al@example.com');
  assert.ok(Math.abs(a.requestedAt.toMillis() - Date.now()) < 60000, 'the server\'s time');
  await call('requestApproval', 'bo', { platform: 'android' }, { ...APPLE, email: 'bo@example.com', email_verified: false });
  const b = await stored('approvals/bo');
  assert.deepEqual(Object.keys(b).sort(), ['platform', 'provider', 'requestedAt', 'state'], 'no name, no unverified email');
  assert.equal(b.provider, 'apple.com');
});

test('D-254 requestApproval: a pending request is refreshed, never its time; a decision never changes', async () => {
  await call('requestApproval', 'al', { name: 'Al', platform: 'ios' }, { email: 'al@example.com', email_verified: true });
  const first = (await stored('approvals/al')).requestedAt.toMillis();
  await new Promise((r) => setTimeout(r, 20));
  assert.deepEqual((await call('requestApproval', 'al', { name: 'Al Ray', platform: 'android' })).result, { state: 'pending' });
  const again = await stored('approvals/al');
  assert.equal(again.name, 'Al Ray');
  assert.equal(again.platform, 'android');
  assert.equal(again.email, undefined, 'an email no longer verified is dropped');
  assert.equal(again.requestedAt.toMillis(), first, 'requestedAt kept');
  await call('requestApproval', 'al', { platform: 'android' });
  assert.equal((await stored('approvals/al')).name, undefined, 'a name no longer given is dropped');
  for (const state of ['approved', 'denied']) {
    await setState('al', state, { name: 'Kept', decidedBy: 'admin' });
    const before = await stored('approvals/al');
    assert.deepEqual((await call('requestApproval', 'al', { name: 'Changed', platform: 'ios' })).result, { state });
    assert.deepEqual(await stored('approvals/al'), before, `${state}: unchanged`);
  }
});

test('D-254 requestApproval: fields smuggled into the request never reach the document', async () => {
  const smuggled = { platform: 'ios', name: 'Al', state: 'approved', decidedBy: 'admin', decidedAt: 1, requestedAt: 0, email: 'x@example.com', provider: 'apple.com', deleteAt: 1 };
  assert.deepEqual((await call('requestApproval', 'al', smuggled)).result, { state: 'pending' });
  const a = await stored('approvals/al');
  assert.deepEqual(Object.keys(a).sort(), ['name', 'platform', 'provider', 'requestedAt', 'state'], 'only what requestApproval writes');
  assert.equal(a.state, 'pending');
  assert.equal(a.provider, 'google.com', 'the token\'s provider, not the request\'s');
  assert.ok(Math.abs(a.requestedAt.toMillis() - Date.now()) < 60000, 'the server\'s time, not 0');
  assert.equal((await call('suggestUsernames', 'al', { name: 'Al' })).details?.approval, 'pending', 'and the gate still refuses');
  // A pending request refreshed with the same smuggling: still only the refresh.
  assert.deepEqual((await call('requestApproval', 'al', smuggled)).result, { state: 'pending' });
  assert.deepEqual(await stored('approvals/al'), a, 'nothing changed');
});

test('D-254 requestApproval: bad input is refused and writes nothing; signing in comes first', async () => {
  for (const bad of [{}, { platform: 'web' }, { platform: 'ios', name: 'x'.repeat(61) }, { platform: 'ios', name: 7 }]) {
    assert.equal((await call('requestApproval', 'al', bad)).error, 'INVALID_ARGUMENT', JSON.stringify(bad));
  }
  assert.equal(await stored('approvals/al'), null);
  assert.equal((await call('requestApproval', null, { platform: 'ios' })).error, 'UNAUTHENTICATED');
});

test('D-254 adminApprovals: anyone but the admin uid signed in with Google is refused, with their own uid', async () => {
  await setState('eve', 'approved');
  for (const [uid, claims] of [['eve', {}], ['al', {}], [ADMIN, APPLE]]) {
    for (const action of ['list', 'decide', 'migrate']) {
      assert.deepEqual(await call('adminApprovals', uid, { action, uid: 'al', state: 'approved' }, claims),
        { error: 'PERMISSION_DENIED', message: 'This is not the admin account.', details: { uid } }, `${uid} ${action}`);
    }
  }
  assert.equal((await call('adminApprovals', null, { action: 'list' })).error, 'UNAUTHENTICATED');
  assert.equal(await stored('meta/approvalsMigration'), null, 'nothing ran');
  // The admin itself needs no approval document, and an unknown action is refused.
  assert.ok((await call('adminApprovals', ADMIN, { action: 'list' })).result);
  assert.equal((await call('adminApprovals', ADMIN, { action: 'nope' })).error, 'INVALID_ARGUMENT');
});

// The Functions emulator forces every callable's CORS open (firebase-tools sets
// the `enableCors` debug feature), so a preflight to it proves nothing: the
// list adminApprovals passes is run through the cors package the Functions
// themselves use (firebase/functions/node_modules/cors), as a preflight.
test('D-254 adminApprovals: CORS answers the admin site and localhost only', async () => {
  assert.match(source, /export const adminApprovals = onCall\(\{ \.\.\.CALLABLE, cors: approvals\.ADMIN_ORIGINS \}, /, 'the list is the one passed');
  const cors = createRequire(new URL('../functions/index.js', import.meta.url))('cors');
  const preflight = (origin) => new Promise((resolve) => {
    const headers = {};
    const res = { statusCode: 200, setHeader: (k, v) => { headers[k.toLowerCase()] = v; }, getHeader: (k) => headers[k.toLowerCase()], end: () => resolve(headers['access-control-allow-origin'] ?? null) };
    cors({ origin: approvals.ADMIN_ORIGINS, methods: 'POST' })(
      { method: 'OPTIONS', headers: { origin, 'access-control-request-method': 'POST' } }, res, () => resolve('next'));
  });
  for (const origin of ['https://skipti-26-admin.web.app', 'https://skipti-26-admin.firebaseapp.com', 'http://localhost:5002', 'http://127.0.0.1:8080']) {
    assert.equal(await preflight(origin), origin, origin);
  }
  for (const origin of ['https://evil.example.com', 'https://skipti-26-admin.web.app.evil.example.com', 'https://skipti.vardrlabs.com', 'http://localhost.evil.example.com']) {
    assert.notEqual(await preflight(origin), origin, origin);
  }
});

test('D-254 adminApprovals list: three lists newest first, the rows\' fields, and the migration banner\'s numbers', async () => {
  const at = (ms) => Timestamp.fromMillis(ms);
  await db.doc('approvals/p1').set({ state: 'pending', requestedAt: at(1000), name: 'Al', platform: 'ios', provider: 'google.com', email: 'al@example.com' });
  await db.doc('approvals/p2').set({ state: 'pending', requestedAt: at(3000), platform: 'android', provider: 'apple.com' });
  await db.doc('approvals/a1').set({ state: 'approved', requestedAt: at(2000), decidedAt: at(2500), decidedBy: 'admin', platform: 'ios' });
  await db.doc('approvals/d1').set({ state: 'denied', requestedAt: at(500), decidedAt: at(600), decidedBy: 'admin', platform: 'android',
    deleteAt: at(9000), deletionRequestedAt: at(8000) });
  await db.doc('approvals/marker-only').set({ deleteAt: at(9000) });
  await db.doc('users/p2').set({ participantId: P, createdAt: at(100) }); // a profile from before the gate, pending
  await db.doc('users/old').set({ participantId: P, createdAt: at(100) }); // no approval at all
  await db.doc('users/a1').set({ participantId: P, createdAt: at(100) });
  const { result } = await call('adminApprovals', ADMIN, { action: 'list' });
  assert.deepEqual(result, {
    pending: [
      { uid: 'p2', state: 'pending', requestedAt: 3000, platform: 'android', provider: 'apple.com' },
      { uid: 'p1', state: 'pending', requestedAt: 1000, name: 'Al', platform: 'ios', provider: 'google.com', email: 'al@example.com' },
    ],
    approved: [{ uid: 'a1', state: 'approved', requestedAt: 2000, decidedAt: 2500, decidedBy: 'admin', platform: 'ios' }],
    denied: [{ uid: 'd1', state: 'denied', requestedAt: 500, decidedAt: 600, decidedBy: 'admin', platform: 'android' }],
    migrated: false,
    toMigrate: 2,
  });
  // Fix wave M1: the banner's number is counted with the marker too (the
  // banner shows while it is above 0, not until the marker exists).
  await db.doc('meta/approvalsMigration').set({ count: 0 });
  const after = (await call('adminApprovals', ADMIN, { action: 'list' })).result;
  assert.equal(after.migrated, true);
  assert.equal(after.toMigrate, 2, 'still two profiles without a decision');
});

// Fix wave M4: past LIST_MAX in one state, the newest requests are listed,
// never the first ones by document id.
test('D-254 adminApprovals list: past 500 in one state, the newest 500 are listed', async () => {
  const batch = db.batch();
  for (let i = 0; i <= 500; i += 1) {
    batch.set(db.doc(`approvals/p${String(i).padStart(3, '0')}`), { state: 'pending', requestedAt: Timestamp.fromMillis(1000 + i), platform: 'ios' });
  }
  await batch.commit();
  const { pending } = (await call('adminApprovals', ADMIN, { action: 'list' })).result;
  assert.equal(pending.length, 500);
  assert.equal(pending[0].uid, 'p500', 'the newest request, last by document id, is listed first');
  assert.equal(pending[499].uid, 'p001');
  assert.ok(!pending.some((r) => r.uid === 'p000'), 'the oldest is the one left out');
});

test('D-254 adminApprovals decide: approves, denies, takes approval away and gives it back; the document must exist', async () => {
  await call('requestApproval', 'al', { name: 'Al', platform: 'ios' }, { email: 'al@example.com', email_verified: true });
  assert.equal((await call('suggestUsernames', 'al', { name: 'Al' })).details?.approval, 'pending');
  assert.deepEqual((await call('adminApprovals', ADMIN, { action: 'decide', uid: 'al', state: 'approved' })).result, { ok: true });
  let a = await stored('approvals/al');
  assert.equal(a.state, 'approved');
  assert.equal(a.decidedBy, 'admin');
  assert.ok(Math.abs(a.decidedAt.toMillis() - Date.now()) < 60000);
  assert.equal(a.name, 'Al', 'the request is kept');
  assert.equal(a.email, 'al@example.com', 'approved: the email is kept');
  assert.ok((await call('suggestUsernames', 'al', { name: 'Al' })).result, 'approved: the gate opens');
  assert.equal(await write('al', 'groups/g1', group('al')), OK, 'and the rules');
  assert.deepEqual((await call('adminApprovals', ADMIN, { action: 'decide', uid: 'al', state: 'denied' })).result, { ok: true });
  a = await stored('approvals/al');
  assert.equal(a.state, 'denied');
  // Fix wave M5: a denial keeps no name or email; the provider, platform and times stay.
  assert.equal(a.name, undefined, 'denied: the name is dropped');
  assert.equal(a.email, undefined, 'denied: the email is dropped');
  assert.deepEqual([a.platform, a.provider, a.decidedBy], ['ios', 'google.com', 'admin']);
  assert.ok(a.requestedAt && a.decidedAt);
  assert.equal((await call('suggestUsernames', 'al', { name: 'Al' })).details?.approval, 'denied');
  assert.equal(await write('al', 'groups/g1', { name: 'Mine' }, 'update'), DENIED);
  assert.deepEqual((await call('adminApprovals', ADMIN, { action: 'decide', uid: 'al', state: 'approved' })).result, { ok: true });
  assert.equal((await stored('approvals/al')).state, 'approved');
  // A pending request denied straight away drops them too.
  await call('requestApproval', 'cy', { name: 'Cy', platform: 'android' }, { email: 'cy@example.com', email_verified: true });
  await call('adminApprovals', ADMIN, { action: 'decide', uid: 'cy', state: 'denied' });
  assert.deepEqual(Object.keys(await stored('approvals/cy')).sort(), ['decidedAt', 'decidedBy', 'platform', 'provider', 'requestedAt', 'state']);
  assert.equal((await call('adminApprovals', ADMIN, { action: 'decide', uid: 'nobody', state: 'approved' })).error, 'NOT_FOUND');
  assert.equal(await stored('approvals/nobody'), null, 'never made');
  for (const bad of [{ uid: 'al', state: 'pending' }, { uid: 'al' }, { uid: 'a/b', state: 'approved' }, { state: 'approved' }]) {
    assert.equal((await call('adminApprovals', ADMIN, { action: 'decide', ...bad })).error, 'INVALID_ARGUMENT', JSON.stringify(bad));
  }
});

test('D-254 adminApprovals migrate: profiles with no decision approved, denied left alone, accounts without a profile untouched; it runs again', async () => {
  await db.doc('users/old').set({ participantId: P, createdAt: Timestamp.fromMillis(1234) });
  await db.doc('users/waiting').set({ participantId: P });
  await db.doc('approvals/waiting').set({ state: 'pending', requestedAt: Timestamp.fromMillis(5000), name: 'Wai', platform: 'ios' });
  await db.doc('users/refused').set({ participantId: P });
  await db.doc('approvals/refused').set({ state: 'denied', requestedAt: Timestamp.fromMillis(10), decidedBy: 'admin' });
  await db.doc('users/fine').set({ participantId: P });
  await db.doc('approvals/fine').set({ state: 'approved', requestedAt: Timestamp.fromMillis(10), decidedBy: 'admin' });
  await db.doc('approvals/new').set({ state: 'pending', requestedAt: Timestamp.fromMillis(6000), platform: 'android' });
  assert.equal((await call('adminApprovals', ADMIN, { action: 'list' })).result.toMigrate, 2);
  assert.deepEqual((await call('adminApprovals', ADMIN, { action: 'migrate' })).result, { approved: 2 });
  const old = await stored('approvals/old');
  assert.equal(old.state, 'approved');
  assert.equal(old.decidedBy, 'migration');
  assert.equal(old.requestedAt.toMillis(), 1234, 'its profile\'s creation');
  const waiting = await stored('approvals/waiting');
  assert.deepEqual([waiting.state, waiting.decidedBy, waiting.name, waiting.requestedAt.toMillis()], ['approved', 'migration', 'Wai', 5000]);
  assert.deepEqual([(await stored('approvals/refused')).state, (await stored('approvals/refused')).decidedBy], ['denied', 'admin']);
  assert.equal((await stored('approvals/fine')).decidedBy, 'admin', 'an approved one is not rewritten');
  assert.equal((await stored('approvals/new')).state, 'pending', 'no profile: a request made after the gate waits');
  const first = await stored('meta/approvalsMigration');
  assert.deepEqual([first.count, first.runs], [2, 1]);
  assert.ok(first.at);
  let listed = (await call('adminApprovals', ADMIN, { action: 'list' })).result;
  assert.deepEqual([listed.migrated, listed.toMigrate], [true, 0]);
  // Fix wave M1: it runs again. With nothing left it changes nothing (idempotent).
  const before = (await db.collection('approvals').get()).docs.map((d) => [d.id, d.data()]);
  assert.deepEqual((await call('adminApprovals', ADMIN, { action: 'migrate' })).result, { approved: 0 }, 'a second run is allowed');
  assert.deepEqual((await db.collection('approvals').get()).docs.map((d) => [d.id, d.data()]), before, 'and changes no approval');
  // A 0.5.0 sign-up in the deploy window (a profile, no request): the banner
  // shows it, and a second press approves it; the marker keeps the first run.
  await db.doc('users/window').set({ participantId: P, createdAt: Timestamp.fromMillis(7777) });
  listed = (await call('adminApprovals', ADMIN, { action: 'list' })).result;
  assert.deepEqual([listed.migrated, listed.toMigrate], [true, 1]);
  assert.deepEqual((await call('adminApprovals', ADMIN, { action: 'migrate' })).result, { approved: 1 });
  assert.deepEqual([(await stored('approvals/window')).state, (await stored('approvals/window')).decidedBy], ['approved', 'migration']);
  const marker = await stored('meta/approvalsMigration');
  assert.deepEqual([marker.count, marker.runs, marker.at.toMillis()], [2, 3, first.at.toMillis()], 'the first run recorded, the runs counted');
  assert.equal((await call('adminApprovals', ADMIN, { action: 'list' })).result.toMigrate, 0);
});

// Fix wave M2: the page's write reads every profile and approval again in its
// transaction. Candidates read before a denial or a purge are passed in here
// directly, as a run that raced one would hold them.
test('D-254 migrate\'s write: a denial or a purge since the page was read wins', async () => {
  await db.doc('users/ok').set({ participantId: P, createdAt: Timestamp.fromMillis(4321) });
  await db.doc('users/refused').set({ participantId: P });
  await db.doc('approvals/refused').set({ state: 'denied', requestedAt: Timestamp.fromMillis(10), decidedBy: 'admin' });
  await db.doc('users/fine').set({ participantId: P });
  await db.doc('approvals/fine').set({ state: 'approved', requestedAt: Timestamp.fromMillis(10), decidedBy: 'admin' });
  // 'gone' was purged: no profile, no approval left.
  assert.equal(await approveFromBeforeGate(db, ['ok', 'refused', 'gone', 'fine']), 1, 'only what this call wrote');
  assert.deepEqual([(await stored('approvals/ok')).state, (await stored('approvals/ok')).requestedAt.toMillis()], ['approved', 4321]);
  const refused = await stored('approvals/refused');
  assert.deepEqual([refused.state, refused.decidedBy], ['denied', 'admin'], 'a denial is never overwritten');
  assert.equal(await stored('approvals/gone'), null, 'a purged account\'s approval is never made again');
  assert.equal((await stored('approvals/fine')).decidedBy, 'admin', 'an approval is not rewritten');
  assert.equal(await approveFromBeforeGate(db, []), 0);
});

test('D-254 adminApprovals migrate: more profiles than one batch of 400 are all approved', async () => {
  const batch = db.batch();
  for (let i = 0; i < 450; i += 1) batch.set(db.doc(`users/u${String(i).padStart(3, '0')}`), { participantId: P });
  await batch.commit();
  await db.doc('approvals/u007').set({ state: 'denied', requestedAt: Timestamp.fromMillis(1) });
  assert.equal((await call('adminApprovals', ADMIN, { action: 'list' })).result.toMigrate, 449);
  assert.deepEqual((await call('adminApprovals', ADMIN, { action: 'migrate' })).result, { approved: 449 });
  const states = (await db.collection('approvals').get()).docs.map((d) => d.data().state);
  assert.equal(states.filter((s) => s === 'approved').length, 449);
  assert.equal(states.filter((s) => s === 'denied').length, 1);
});

test('D-254: a pending account with no profile asks to be deleted; the marker is on its approval, and keeping it clears it', async () => {
  await authUser('wai');
  await call('requestApproval', 'wai', { name: 'Wai', platform: 'ios' });
  await db.doc(`users/wai/devices/${D}`).set({ name: 'iPhone', platform: 'ios', fcmToken: 't1' });
  const { result } = await call('requestDeletion', 'wai', {});
  const days = (result.deleteAt - Date.now()) / 86400000;
  assert.ok(days > 6.99 && days <= 7, `${days} days`);
  let a = await stored('approvals/wai');
  assert.equal(a.deleteAt.toMillis(), result.deleteAt);
  assert.ok(a.deletionRequestedAt);
  assert.equal(a.state, 'pending', 'the request stays');
  assert.equal((await stored(`users/wai/devices/${D}`)).signedOut, true, 'every phone signed out');
  assert.equal((await db.doc('users/wai').get()).exists, false, 'no profile made');
  assert.deepEqual((await call('cancelDeletion', 'wai', {})).result, { cancelled: true });
  a = await stored('approvals/wai');
  assert.deepEqual([a.deleteAt, a.deletionRequestedAt, a.state, a.name], [undefined, undefined, 'pending', 'Wai']);
  // An account that never asked for approval: the marker alone, and keeping it leaves nothing.
  await authUser('never');
  assert.ok((await call('requestDeletion', 'never', {})).result.deleteAt);
  assert.deepEqual(Object.keys(await stored('approvals/never')).sort(), ['deleteAt', 'deletionRequestedAt']);
  assert.equal((await call('suggestUsernames', 'never', { name: 'N' })).details?.approval, 'none', 'a marker is no approval');
  await call('cancelDeletion', 'never', {});
  assert.equal(await stored('approvals/never'), null);
  // An account with a profile keeps the marker on its profile, as before.
  await authUser('al');
  await setState('al', 'approved');
  await db.doc('users/al').set({ participantId: P });
  await call('requestDeletion', 'al', {});
  assert.ok((await stored('users/al')).deleteAt);
  assert.equal((await stored('approvals/al')).deleteAt, undefined);
});

test('D-254: the purge takes a profile-less account past its grace (approval and Auth user), and every purge deletes the approval', async () => {
  await adminAuth.createUser({ uid: 'wai' });
  await adminAuth.createUser({ uid: 'soon' });
  await adminAuth.createUser({ uid: 'al' });
  await db.doc('approvals/wai').set({ state: 'pending', requestedAt: Timestamp.fromMillis(1), deleteAt: Timestamp.fromMillis(Date.now() - 1000) });
  await db.doc('approvals/soon').set({ state: 'denied', requestedAt: Timestamp.fromMillis(1), deleteAt: Timestamp.fromMillis(Date.now() + 3600000) });
  // Marked on both (approved in its grace, then a profile): taken once.
  await db.doc('users/al').set({ participantId: P, deleteAt: Timestamp.fromMillis(Date.now() - 2000) });
  await db.doc('approvals/al').set({ state: 'approved', requestedAt: Timestamp.fromMillis(1), deleteAt: Timestamp.fromMillis(Date.now() - 500) });
  assert.deepEqual(await purgeDue(db, adminAuth, Date.now()), { purged: ['al', 'wai'], failed: [], more: false });
  assert.equal(await stored('approvals/wai'), null);
  await assert.rejects(adminAuth.getUser('wai'), (e) => e.code === 'auth/user-not-found');
  assert.equal(await stored('approvals/al'), null);
  assert.equal(await stored('users/al'), null);
  assert.equal((await stored('approvals/soon')).state, 'denied', 'not due yet');
  assert.ok(await adminAuth.getUser('soon'));
  // purgeAccount itself (any account): the approval goes with the rest.
  await db.doc('users/bo').set({ participantId: P });
  await db.doc('approvals/bo').set({ state: 'approved' });
  await purgeAccount(db, adminAuth, 'bo');
  assert.equal(await stored('approvals/bo'), null);
  // The approvals marker pages are bounded too, and say when they stopped.
  for (const [uid, ago] of [['x1', 3000], ['x2', 2000]]) {
    await db.doc(`approvals/${uid}`).set({ state: 'pending', deleteAt: Timestamp.fromMillis(Date.now() - ago) });
  }
  assert.deepEqual(await purgeDue(db, adminAuth, Date.now(), null, { pageSize: 1, maxPages: 1 }), { purged: ['x1'], failed: [], more: true });
  assert.deepEqual(await purgeDue(db, adminAuth, Date.now(), null, { pageSize: 1, maxPages: 1 }), { purged: ['x2'], failed: [], more: false });
});
