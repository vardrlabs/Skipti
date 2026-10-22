// Refusal tests for firebase/firestore.rules and firebase/storage.rules,
// against the local emulators (project demo-skipti: a `demo-` project never
// reaches production). No dependency beyond Node's own test runner and fetch:
// requests go to the emulators' REST endpoints with the unsigned ID tokens the
// emulators accept. Run by scripts/firebase-rules-test.sh (a full-gate leg).
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

const PROJECT = 'demo-skipti';
const FS = `http://127.0.0.1:${process.env.FIRESTORE_PORT ?? 8180}`;
const ST = `http://127.0.0.1:${process.env.STORAGE_PORT ?? 9199}`;
const BUCKET = `${PROJECT}.appspot.com`;
const DOCS = `projects/${PROJECT}/databases/(default)/documents`;

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
function token(uid) {
  const now = Math.floor(Date.now() / 1000);
  return `${b64({ alg: 'none', typ: 'JWT' })}.${b64({
    iss: `https://securetoken.google.com/${PROJECT}`, aud: PROJECT, iat: now, exp: now + 3600,
    auth_time: now, sub: uid, user_id: uid,
    firebase: { sign_in_provider: 'google.com', identities: {} },
  })}.`;
}
const auth = (uid) => (uid === 'owner' ? 'Bearer owner' : uid ? `Bearer ${token(uid)}` : undefined);

// Firestore REST values.
function value(v) {
  if (v === SERVER_TIME) throw new Error('server time is a transform');
  if (v === null) return { nullValue: null };
  if (typeof v === 'string') return { stringValue: v };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (typeof v === 'number') return { integerValue: String(v) };
  if (v instanceof Date) return { timestampValue: v.toISOString() };
  if (v instanceof Uint8Array) return { bytesValue: Buffer.from(v).toString('base64') };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(value) } };
  return { mapValue: { fields: fields(v) } };
}
const SERVER_TIME = Symbol('serverTime');
const DROP = Symbol('drop'); // D-258: in an 'update', the field is in the mask and absent, so it is removed
function fields(o) {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== SERVER_TIME && v !== DROP).map(([k, v]) => [k, value(v)]));
}

// One write through :commit, so server timestamps and preconditions work.
// mode: 'create' (must not exist), 'set' (no precondition: what the apps'
// setData sends), 'update' (merge the given fields), 'delete'.
// D-254: every account these tests act as is approved (approvals/{uid},
// written as the owner, once a test, before its first request), so each
// refusal here is refused for the reason it names, never for the approval
// gate. The gate's own refusals are in approvals.test.mjs.
let approvedUids = new Set();
async function approve(uid) {
  if (!uid || uid === 'owner' || approvedUids.has(uid)) return;
  approvedUids.add(uid);
  assert.equal(await write('owner', `approvals/${uid}`, { state: 'approved' }, 'set'), 200, `approve ${uid}`);
}

async function write(uid, path, data, mode = 'create') {
  await approve(uid);
  const name = `${DOCS}/${path}`;
  let w;
  if (mode === 'delete') {
    w = { delete: name };
  } else {
    const plain = Object.fromEntries(Object.entries(data).filter(([, v]) => v !== SERVER_TIME));
    w = { update: { name, fields: fields(plain) } };
    const stamps = Object.entries(data).filter(([, v]) => v === SERVER_TIME).map(([k]) => k);
    if (stamps.length) w.updateTransforms = stamps.map((f) => ({ fieldPath: f, setToServerValue: 'REQUEST_TIME' }));
    if (mode === 'create') w.currentDocument = { exists: false };
    if (mode === 'update') {
      w.currentDocument = { exists: true };
      w.updateMask = { fieldPaths: Object.keys(plain) };
    }
  }
  const r = await fetch(`${FS}/v1/projects/${PROJECT}/databases/(default)/documents:commit`, {
    method: 'POST', headers: { 'content-type': 'application/json', ...(auth(uid) && { authorization: auth(uid) }) },
    body: JSON.stringify({ writes: [w] }),
  });
  return r.status;
}
async function get(uid, path) {
  await approve(uid);
  const r = await fetch(`${FS}/v1/${DOCS}/${path}`, { headers: auth(uid) ? { authorization: auth(uid) } : {} });
  return r.status;
}
async function list(uid, parent, where) {
  await approve(uid);
  const [collectionId] = parent.split('/').slice(-1);
  const prefix = parent.split('/').slice(0, -1).join('/');
  const q = { structuredQuery: { from: [{ collectionId }] } };
  if (where) {
    q.structuredQuery.where = { fieldFilter: { field: { fieldPath: where[0] }, op: where[1], value: value(where[2]) } };
  }
  const r = await fetch(`${FS}/v1/${DOCS}${prefix ? '/' + prefix : ''}:runQuery`, {
    method: 'POST', headers: { 'content-type': 'application/json', ...(auth(uid) && { authorization: auth(uid) }) },
    body: JSON.stringify(q),
  });
  return r.status;
}
async function upload(uid, path, bytes, contentType = 'image/jpeg') {
  await approve(uid);
  const r = await fetch(`${ST}/v0/b/${BUCKET}/o?name=${encodeURIComponent(path)}`, {
    method: 'POST', headers: { 'content-type': contentType, ...(uid && { authorization: `Firebase ${token(uid)}` }) },
    body: bytes,
  });
  return r.status;
}
async function download(uid, path) {
  await approve(uid);
  const r = await fetch(`${ST}/v0/b/${BUCKET}/o/${encodeURIComponent(path)}?alt=media`, {
    headers: uid ? { authorization: `Firebase ${token(uid)}` } : {},
  });
  await r.arrayBuffer();
  return r.status;
}

const OK = 200;
const DENIED = 403;
const P = 'a'.repeat(32); // a participant id
const D = 'b'.repeat(32); // a device id
// D-235: a record as both shells write it (CloudRecords.document): a type
// digit, a typed stamp, the header's integers, the nonce and the blob as bytes.
const record = (author) => ({
  type: '1', hlc: { millis: 1790000000123, counter: 0, device: D }, authorUid: author, deviceId: D,
  createdAt: SERVER_TIME,
  payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1, 2, 3, 4]),
    blob: new Uint8Array(Buffer.from('{"amountMinor":120000,"currency":"INR"}')) },
});
// D-196: the push line a group expense record carries.
const E = `1790000000123:7:${D}`; // an expense id (core's millis:counter:device)
const notice = (text = 'Alice added Dinner · ₹1,200.00') => ({ text, expense: E });
const profile = () => ({ participantId: P, createdAt: SERVER_TIME, updatedAt: SERVER_TIME });
const device = () => ({ name: 'Pixel 8', platform: 'android', appVersion: '0.4.0', lastSeen: SERVER_TIME });
const group = (uid) => ({ name: 'Goa trip', memberUids: [uid], createdBy: uid, createdAt: SERVER_TIME });

beforeEach(async () => {
  const r = await fetch(`${FS}/emulator/v1/${DOCS}`, { method: 'DELETE' });
  assert.equal(r.status, OK, 'the Firestore emulator must be running');
  approvedUids = new Set(); // D-254: the wipe took the approvals too
});

// --- users/{uid} ---------------------------------------------------------
test('a profile is written and read only by its account', async () => {
  assert.equal(await write('alice', 'users/alice', profile()), OK);
  assert.equal(await get('alice', 'users/alice'), OK);
  assert.equal(await get('bob', 'users/alice'), DENIED);
  assert.equal(await get(null, 'users/alice'), DENIED);
  assert.equal(await write('bob', 'users/carol', profile()), DENIED);
  assert.equal(await write(null, 'users/carol', profile()), DENIED);
});
test('nobody can list the accounts', async () => {
  assert.equal(await write('alice', 'users/alice', profile()), OK);
  assert.equal(await list('alice', 'users'), DENIED);
});
test('a profile refuses unknown fields, a bad participant id and a client time', async () => {
  assert.equal(await write('alice', 'users/alice', { ...profile(), email: 'a@b.c' }), DENIED);
  assert.equal(await write('alice', 'users/alice', { ...profile(), participantId: 'not-hex' }), DENIED);
  assert.equal(await write('alice', 'users/alice', { ...profile(), createdAt: new Date(0) }), DENIED);
});
test('D-197: the phone writes the name others see, short; never its username, findable, email fingerprint or change counter', async () => {
  assert.equal(await write('alice', 'users/alice', { ...profile(), name: 'Alice' }), OK);
  assert.equal(await write('alice', 'users/alice', { name: 'Ally', updatedAt: SERVER_TIME }, 'update'), OK);
  assert.equal(await write('alice', 'users/alice', { name: 'x'.repeat(61), updatedAt: SERVER_TIME }, 'update'), DENIED);
  assert.equal(await write('alice', 'users/alice', { name: '', updatedAt: SERVER_TIME }, 'update'), DENIED);
  for (const field of [{ username: 'alice' }, { findable: false }, { emailHash: 'a'.repeat(64) }, { usernameChanged: false }]) {
    assert.equal(await write('alice', 'users/alice', { ...field, updatedAt: SERVER_TIME }, 'update'), DENIED, JSON.stringify(field));
  }
  assert.equal(await write('bob', 'users/bob', { ...profile(), username: 'bob' }), DENIED, 'not at create either');
  assert.equal(await write('carol', 'users/carol', { ...profile(), usernameChanged: false }), DENIED, 'C2: the counter is Functions only');
  assert.equal(await write('bob', 'users/alice', { name: 'Mallory', updatedAt: SERVER_TIME }, 'update'), DENIED);
});
test('D-197: nobody reads or writes the daily counters', async () => {
  assert.equal(await write('owner', 'limits/alice', { day: '2026-10-02', searches: 1 }), OK);
  assert.equal(await get('alice', 'limits/alice'), DENIED);
  assert.equal(await write('alice', 'limits/alice', { day: '2026-10-02', searches: 0 }, 'set'), DENIED);
});
test('a profile update changes only its time; participant id is fixed', async () => {
  assert.equal(await write('alice', 'users/alice', profile()), OK);
  assert.equal(await write('alice', 'users/alice', { updatedAt: SERVER_TIME }, 'update'), OK);
  assert.equal(await write('alice', 'users/alice', { participantId: 'c'.repeat(32), updatedAt: SERVER_TIME }, 'update'), DENIED);
  assert.equal(await write('alice', 'users/alice', null, 'delete'), DENIED);
});

// --- users/{uid}/devices ----------------------------------------------------
test('a phone registers itself under its own account only', async () => {
  assert.equal(await write('alice', `users/alice/devices/${D}`, device()), OK);
  assert.equal(await get('alice', `users/alice/devices/${D}`), OK);
  assert.equal(await list('alice', 'users/alice/devices'), OK);
  assert.equal(await get('bob', `users/alice/devices/${D}`), DENIED);
  assert.equal(await list('bob', 'users/alice/devices'), DENIED);
  assert.equal(await write('bob', `users/alice/devices/${'c'.repeat(32)}`, device()), DENIED);
});
test('a phone can neither set nor clear signedOut', async () => {
  assert.equal(await write('alice', `users/alice/devices/${D}`, { ...device(), signedOut: false }), DENIED);
  assert.equal(await write('owner', `users/alice/devices/${D}`, { ...device(), signedOut: true }), OK);
  assert.equal(await write('alice', `users/alice/devices/${D}`, { signedOut: false }, 'update'), DENIED);
});
test('a device update refreshes lastSeen with its name and version', async () => {
  assert.equal(await write('alice', `users/alice/devices/${D}`, device()), OK);
  assert.equal(await write('alice', `users/alice/devices/${D}`, { appVersion: '0.4.1', lastSeen: SERVER_TIME }, 'update'), OK);
  assert.equal(await write('alice', `users/alice/devices/${D}`, { platform: 'ios', lastSeen: SERVER_TIME }, 'update'), DENIED);
  assert.equal(await write('alice', `users/alice/devices/${D}`, { lastSeen: new Date(0) }, 'update'), DENIED);
});
test('D-196: a phone keeps its own push address, and only a short string', async () => {
  const T = 'fcm-token:' + 'x'.repeat(150);
  assert.equal(await write('alice', `users/alice/devices/${D}`, { ...device(), fcmToken: T }), OK);
  assert.equal(await write('alice', `users/alice/devices/${D}`, { fcmToken: T + 'y', lastSeen: SERVER_TIME }, 'update'), OK);
  assert.equal(await write('alice', `users/alice/devices/${D}`, { fcmToken: T, lastSeen: new Date(0) }, 'update'), DENIED,
    'a token change refreshes lastSeen with the server time');
  assert.equal(await write('alice', `users/alice/devices/${D}`, { fcmToken: 'x'.repeat(4097), lastSeen: SERVER_TIME }, 'update'), DENIED);
  assert.equal(await write('alice', `users/alice/devices/${D}`, { fcmToken: '', lastSeen: SERVER_TIME }, 'update'), DENIED);
  assert.equal(await write('alice', `users/alice/devices/${D}`, { fcmToken: 42, lastSeen: SERVER_TIME }, 'update'), DENIED);
  assert.equal(await write('bob', `users/alice/devices/${D}`, { fcmToken: T, lastSeen: SERVER_TIME }, 'update'), DENIED);
  assert.equal(await write('alice', `users/alice/devices/${'c'.repeat(32)}`, { ...device(), fcmToken: 'x'.repeat(4097) }), DENIED);
});
test('a device id is 32 hex and a device name is short', async () => {
  assert.equal(await write('alice', 'users/alice/devices/phone1', device()), DENIED);
  assert.equal(await write('alice', `users/alice/devices/${D}`, { ...device(), name: 'x'.repeat(61) }), DENIED);
  assert.equal(await write('alice', `users/alice/devices/${D}`, { ...device(), platform: 'web' }), DENIED);
});
test('a phone deletes only its own account\'s device', async () => {
  assert.equal(await write('alice', `users/alice/devices/${D}`, device()), OK);
  assert.equal(await write('bob', `users/alice/devices/${D}`, null, 'delete'), DENIED);
  assert.equal(await write('alice', `users/alice/devices/${D}`, null, 'delete'), OK);
});

// --- users/{uid}/records (personal ledger) ---------------------------------
test('personal records: only the account reads and writes them', async () => {
  assert.equal(await write('alice', 'users/alice/records/r1', record('alice')), OK);
  assert.equal(await get('alice', 'users/alice/records/r1'), OK);
  assert.equal(await list('alice', 'users/alice/records'), OK);
  assert.equal(await get('bob', 'users/alice/records/r1'), DENIED);
  assert.equal(await list('bob', 'users/alice/records'), DENIED);
  assert.equal(await write('bob', 'users/alice/records/r2', record('bob')), DENIED);
});
test('a record is immutable, carries its author, and takes the server time', async () => {
  assert.equal(await write('alice', 'users/alice/records/r1', record('alice')), OK);
  assert.equal(await write('alice', 'users/alice/records/r1', { payload: { amountMinor: 1 } }, 'update'), DENIED);
  assert.equal(await write('alice', 'users/alice/records/r1', null, 'delete'), DENIED);
  assert.equal(await write('alice', 'users/alice/records/r2', record('bob')), DENIED);
  assert.equal(await write('alice', 'users/alice/records/r3', { ...record('alice'), createdAt: new Date(0) }), DENIED);
  assert.equal(await write('alice', 'users/alice/records/r4', { ...record('alice'), extra: 1 }), DENIED);
  assert.equal(await write('alice', 'users/alice/records/r5', { ...record('alice'), notice: notice() }), DENIED,
    'a personal record tells nobody: no push line');
});

// --- users/{uid}/invites ------------------------------------------------------
test('invites to an account are written by Functions and dismissed by the account', async () => {
  const inv = { groupId: 'g1', groupName: 'Goa trip', fromName: 'Bob', createdAt: new Date() };
  assert.equal(await write('bob', 'users/alice/invites/i1', inv), DENIED);
  assert.equal(await write('alice', 'users/alice/invites/i0', inv), DENIED);
  assert.equal(await write('owner', 'users/alice/invites/i1', inv), OK);
  assert.equal(await get('alice', 'users/alice/invites/i1'), OK);
  assert.equal(await get('bob', 'users/alice/invites/i1'), DENIED);
  assert.equal(await write('bob', 'users/alice/invites/i1', null, 'delete'), DENIED);
  assert.equal(await write('alice', 'users/alice/invites/i1', null, 'delete'), OK);
});

// --- groups ------------------------------------------------------------------
test('anyone signed in starts a group with only themselves in it', async () => {
  assert.equal(await write('alice', 'groups/g1', group('alice')), OK);
  assert.equal(await write(null, 'groups/g2', group('alice')), DENIED);
  assert.equal(await write('alice', 'groups/g3', { ...group('alice'), memberUids: ['alice', 'bob'] }), DENIED);
  assert.equal(await write('alice', 'groups/g4', group('bob')), DENIED);
  assert.equal(await write('alice', 'groups/g5', { ...group('alice'), createdBy: 'bob' }), DENIED);
  // D-210: roles and removals are Functions only.
  for (const field of [{ owner: 'alice' }, { admins: ['alice'] }, { former: { bob: { at: new Date(), by: 'alice' } } }]) {
    assert.equal(await write('alice', 'groups/g6', { ...group('alice'), ...field }), DENIED, JSON.stringify(field));
  }
});
// D-256 (decision 276): a deleted group leaves deletedGroups/{g} (deleteGroup writes it); its id is never a group again.
const tombstone = () => ({ byUid: 'alice', uids: ['alice', 'bob', 'dan'], deletedAt: new Date() });
test('D-256: a deleted group\'s id can never be created again, by anyone, even as its only member', async () => {
  assert.equal(await write('owner', 'deletedGroups/g1', tombstone()), OK);
  assert.equal(await write('alice', 'groups/g1', group('alice')), DENIED, 'the deleter');
  assert.equal(await write('bob', 'groups/g1', group('bob')), DENIED, 'a member');
  assert.equal(await write('carol', 'groups/g1', group('carol')), DENIED, 'a stranger');
  assert.equal(await write('alice', 'groups/g1', group('alice'), 'set'), DENIED, 'the apps\' setData (ensureGroup) too');
  assert.equal(await write('alice', 'groups/g2', group('alice')), OK, 'a fresh id is still a group');
  assert.equal(await write('carol', 'groups/g3', group('carol'), 'set'), OK);
});
test('D-256: an account the tombstone lists reads it; a stranger does not; nobody lists or writes one', async () => {
  assert.equal(await write('owner', 'deletedGroups/g1', tombstone()), OK, 'a Function (the admin SDK) writes it');
  for (const uid of ['alice', 'bob', 'dan']) assert.equal(await get(uid, 'deletedGroups/g1'), OK, `${uid} (deleter, member, former)`);
  assert.equal(await get('carol', 'deletedGroups/g1'), DENIED, 'a stranger');
  assert.equal(await get(null, 'deletedGroups/g1'), DENIED, 'signed out');
  assert.equal(await get('alice', 'deletedGroups/g9'), DENIED, 'no tombstone reads as refused, never as not found');
  for (const uid of ['alice', 'carol', 'emulator-admin']) {
    assert.equal(await list(uid, 'deletedGroups'), DENIED, `${uid}: list`);
    assert.equal(await list(uid, 'deletedGroups', ['uids', 'ARRAY_CONTAINS', uid]), DENIED, `${uid}: list its own`);
    assert.equal(await write(uid, 'deletedGroups/g4', { ...tombstone(), byUid: uid, uids: [uid] }), DENIED, `${uid}: create`);
    assert.equal(await write(uid, 'deletedGroups/g1', { uids: ['alice', 'bob', 'dan', uid] }, 'update'), DENIED, `${uid}: update`);
    assert.equal(await write(uid, 'deletedGroups/g1', null, 'delete'), DENIED, `${uid}: delete`);
  }
  assert.equal(await get('alice', 'deletedGroups/g1'), OK, 'still there, unchanged');
  assert.equal(await get('carol', 'deletedGroups/g1'), DENIED, 'nobody added themselves');
});
test('a group is read only by its members, and listed only by membership', async () => {
  assert.equal(await write('alice', 'groups/g1', group('alice')), OK);
  assert.equal(await get('alice', 'groups/g1'), OK);
  assert.equal(await get('bob', 'groups/g1'), DENIED);
  assert.equal(await list('alice', 'groups', ['memberUids', 'ARRAY_CONTAINS', 'alice']), OK);
  assert.equal(await list('bob', 'groups'), DENIED);
});
test('no client changes who is in a group, and none deletes it', async () => {
  assert.equal(await write('alice', 'groups/g1', group('alice')), OK);
  assert.equal(await write('alice', 'groups/g1', { memberUids: ['alice', 'bob'] }, 'update'), DENIED);
  assert.equal(await write('bob', 'groups/g1', { memberUids: ['alice', 'bob'] }, 'update'), DENIED);
  assert.equal(await write('alice', 'groups/g1', { createdBy: 'bob' }, 'update'), DENIED);
  assert.equal(await write('alice', 'groups/g1', null, 'delete'), DENIED);
  // D-210: who owns it, who manages it and who was removed: Functions only.
  for (const field of [{ owner: 'alice' }, { admins: ['alice'] }, { former: { bob: { at: new Date(), by: 'alice' } } }]) {
    assert.equal(await write('alice', 'groups/g1', field, 'update'), DENIED, JSON.stringify(field));
  }
});
test('a member renames a group; a stranger cannot', async () => {
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), memberUids: ['alice', 'bob'], createdAt: new Date() }), OK);
  assert.equal(await write('bob', 'groups/g1', { name: 'Goa 2026' }, 'update'), OK);
  assert.equal(await write('carol', 'groups/g1', { name: 'Mine now' }, 'update'), DENIED);
});

// --- groups/{g}/records ----------------------------------------------------------
test('group records: members read and add in their own name; strangers do neither', async () => {
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), memberUids: ['alice', 'bob'], createdAt: new Date() }), OK);
  assert.equal(await write('bob', 'groups/g1/records/r1', record('bob')), OK);
  assert.equal(await get('alice', 'groups/g1/records/r1'), OK);
  assert.equal(await list('alice', 'groups/g1/records'), OK);
  assert.equal(await get('carol', 'groups/g1/records/r1'), DENIED);
  assert.equal(await list('carol', 'groups/g1/records'), DENIED);
  assert.equal(await write('carol', 'groups/g1/records/r2', record('carol')), DENIED);
  assert.equal(await write('bob', 'groups/g1/records/r3', record('alice')), DENIED, 'a forged author');
  assert.equal(await write('alice', 'groups/g1/records/r1', { notice: notice('edited') }, 'update'), DENIED);
  assert.equal(await write('bob', 'groups/g1/records/r1', null, 'delete'), DENIED);
});
test('D-196: a group record carries a push line of a short text and an expense id, nothing else', async () => {
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), memberUids: ['alice', 'bob'], createdAt: new Date() }), OK);
  assert.equal(await write('bob', 'groups/g1/records/n1', { ...record('bob'), notice: notice() }), OK);
  assert.equal(await write('bob', 'groups/g1/records/n2', { ...record('bob'), notice: 'Bob added Dinner' }), DENIED,
    'a bare string');
  assert.equal(await write('bob', 'groups/g1/records/n3', { ...record('bob'), notice: notice('x'.repeat(201)) }), DENIED);
  assert.equal(await write('bob', 'groups/g1/records/n4', { ...record('bob'), notice: notice('') }), DENIED);
  assert.equal(await write('bob', 'groups/g1/records/n5', { ...record('bob'), notice: { text: 'Bob added Dinner' } }), DENIED,
    'no expense id');
  assert.equal(await write('bob', 'groups/g1/records/n6', { ...record('bob'), notice: { ...notice(), expense: 'g1/../x' } }), DENIED);
  assert.equal(await write('bob', 'groups/g1/records/n7', { ...record('bob'), notice: { ...notice(), expense: `1:2:${'B'.repeat(32)}` } }), DENIED);
  assert.equal(await write('bob', 'groups/g1/records/n8', { ...record('bob'), notice: { ...notice(), url: 'https://x' } }), DENIED,
    'nothing beyond text and expense');
});

// D-257 (G-18): the notice's new shape, the fields the Function builds the line from. The old shape above (a 0.5.0
// phone's) is still taken.
const told = (over = {}) => ({ expense: E, kind: 'added', title: 'Dinner', amount: '₹1,200.00', ...over });
test('D-257: a notice of kind, title and amount: the kinds enumerated, one line each, bounded, nothing else', async () => {
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), memberUids: ['alice', 'bob'], createdAt: new Date() }), OK);
  let i = 0;
  const put = (n) => write('bob', `groups/g1/records/k${i++}`, { ...record('bob'), notice: n });
  assert.equal(await put(told()), OK);
  assert.equal(await put(told({ kind: 'changed', title: 'an expense' })), OK);
  const { amount, ...noAmount } = told({ kind: 'deleted' });
  assert.equal(await put(noAmount), OK, 'a delete carries no amount');
  const { amount: _a, ...unpriced } = told();
  assert.equal(await put(unpriced), OK, 'an amount core could not format is left out');
  // The bounds count UTF-16 code units (size(), measured here: an emoji is 2), as the phones cut.
  assert.equal(await put(told({ title: 'x'.repeat(61) })), OK);
  assert.equal(await put(told({ title: `${'😀'.repeat(30)}x` })), OK, '30 emoji and a letter: 61 units');
  assert.equal(await put(told({ title: '€'.repeat(61) })), OK, 'a character of one unit (three UTF-8 bytes) is one');
  assert.equal(await put(told({ title: 'x'.repeat(59) + '…' })), OK, 'the phone\'s cut');
  assert.equal(await put(told({ amount: 'x'.repeat(40) })), OK);
  assert.equal(await put(told({ title: 'Café · 🍕 dinner, l\u2019été' })), OK);
  // Refused.
  assert.equal(await put(told({ kind: 'paid' })), DENIED, 'an unknown kind');
  assert.equal(await put(told({ kind: 'Added' })), DENIED, 'kinds are exact');
  assert.equal(await put(told({ title: 'x'.repeat(62) })), DENIED, 'a 62-character title');
  assert.equal(await put(told({ title: '😀'.repeat(31) })), DENIED, '31 emoji: 62 units');
  assert.equal(await put(told({ title: '' })), DENIED, 'an empty title');
  assert.equal(await put(told({ amount: 'x'.repeat(41) })), DENIED, 'a 41-character amount');
  assert.equal(await put(told({ amount: '' })), DENIED, 'an empty amount');
  assert.equal(await put(told({ title: 'Dinner\nBob owes you everything' })), DENIED, 'a line break in a title');
  assert.equal(await put(told({ title: 'Dinner\rx' })), DENIED, 'a carriage return');
  assert.equal(await put(told({ title: 'Din\u0000ner' })), DENIED, 'a NUL');
  assert.equal(await put(told({ title: 'Din\u0085ner' })), DENIED, 'a C1 control (next line)');
  assert.equal(await put(told({ title: 'Din\u2028ner' })), DENIED, 'a line separator');
  assert.equal(await put(told({ amount: '₹1,200\t.00' })), DENIED, 'a control character in an amount');
  assert.equal(await put(told({ kind: 'deleted' })), DENIED, 'an amount on a delete');
  assert.equal(await put(told({ name: 'Alice' })), DENIED, 'an extra key (a name the phone chose)');
  assert.equal(await put(told({ text: 'Alice added Dinner' })), DENIED, 'the old text beside the new fields');
  const { expense, ...noExpense } = told();
  assert.equal(await put(noExpense), DENIED, 'a missing expense');
  const { kind, ...noKind } = told();
  assert.equal(await put(noKind), DENIED, 'a missing kind');
  const { title, ...noTitle } = told();
  assert.equal(await put(noTitle), DENIED, 'a missing title');
  assert.equal(await put(told({ expense: 'g1/../x' })), DENIED, 'a malformed expense');
  assert.equal(await put(told({ title: 7 })), DENIED, 'a title that is not text');
  assert.equal(await write('alice', 'users/alice/records/k99', { ...record('alice'), notice: told() }), DENIED,
    'a personal record carries none');
});

// --- D-210: removal, the former member's read, requests and notices --------------
test('D-210: a phone never writes a removal record; only removeMember does', async () => {
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), memberUids: ['alice', 'bob'], createdAt: new Date() }), OK);
  assert.equal(await write('bob', 'groups/g1/records/r1', { ...record('bob'), removal: true }), DENIED);
  assert.equal(await write('bob', 'groups/g1/records/r2', { ...record('bob'), removal: false }), DENIED);
  assert.equal(await write('bob', 'groups/g1/records/r3', record('bob')), OK);
});
test('D-211: a phone never writes a profile record; only shareProfile does', async () => {
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), memberUids: ['alice', 'bob'], createdAt: new Date() }), OK);
  assert.equal(await write('bob', 'groups/g1/records/p1', { ...record('bob'), profile: true }), DENIED);
  assert.equal(await write('bob', 'groups/g1/records/p2', { ...record('bob'), profile: false }), DENIED);
  assert.equal(await write('bob', 'groups/g1/records/p3', record('bob')), OK);
});
test('D-210: a removed person reads the group and what was written up to their removal, nothing after', async () => {
  const at = new Date(Date.UTC(2026, 9, 13, 12));
  const before = new Date(at.getTime() - 60000);
  const after = new Date(at.getTime() + 60000);
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), createdAt: before, former: { bob: { at, by: 'alice' } } }), OK);
  assert.equal(await write('owner', 'groups/g1/records/old', { ...record('bob'), createdAt: before }), OK);
  assert.equal(await write('owner', 'groups/g1/records/gone', { ...record('alice'), createdAt: at }), OK);
  assert.equal(await write('owner', 'groups/g1/records/new', { ...record('alice'), createdAt: after }), OK);
  assert.equal(await get('bob', 'groups/g1'), OK);
  assert.equal(await list('bob', 'groups/g1/records', ['createdAt', 'LESS_THAN_OR_EQUAL', at]), OK, 'bounded at the removal');
  assert.equal(await list('bob', 'groups/g1/records', ['createdAt', 'LESS_THAN_OR_EQUAL', before]), OK, 'bounded earlier');
  assert.equal(await list('bob', 'groups/g1/records'), DENIED, 'unbounded');
  assert.equal(await list('bob', 'groups/g1/records', ['createdAt', 'LESS_THAN_OR_EQUAL', after]), DENIED, 'bounded after the removal');
  assert.equal(await get('bob', 'groups/g1/records/old'), OK);
  assert.equal(await get('bob', 'groups/g1/records/gone'), OK, 'the removal itself');
  assert.equal(await get('bob', 'groups/g1/records/new'), DENIED);
  assert.equal(await write('bob', 'groups/g1/records/r9', record('bob')), DENIED, 'nothing can be added');
  assert.equal(await write('bob', 'groups/g1', { name: 'Mine' }, 'update'), DENIED);
  assert.equal(await list('bob', 'groups', ['memberUids', 'ARRAY_CONTAINS', 'bob']), OK, 'their list query asks about membership');
  assert.equal(await get('carol', 'groups/g1'), DENIED, 'a stranger');
  assert.equal(await list('carol', 'groups/g1/records', ['createdAt', 'LESS_THAN_OR_EQUAL', at]), DENIED);
  assert.equal(await get(null, 'groups/g1'), DENIED);
});
test('D-210: removal requests are Functions only', async () => {
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), memberUids: ['alice', 'bob'], createdAt: new Date() }), OK);
  assert.equal(await write('owner', 'groups/g1/requests/x', { member: 'x', askerUid: 'bob' }), OK);
  assert.equal(await get('alice', 'groups/g1/requests/x'), DENIED);
  assert.equal(await list('alice', 'groups/g1/requests'), DENIED);
  assert.equal(await write('alice', 'groups/g1/requests/y', { member: 'y', askerUid: 'alice' }), DENIED);
  assert.equal(await write('bob', 'groups/g1/requests/x', null, 'delete'), DENIED);
});
test('D-210: notices are written by Functions and read and dismissed by the account', async () => {
  const n = { kind: 'removed', groupId: 'g1', groupName: 'Goa trip', byName: 'Alice', at: new Date() };
  assert.equal(await write('alice', 'users/alice/notices/n0', n), DENIED);
  assert.equal(await write('bob', 'users/alice/notices/n0', n), DENIED);
  assert.equal(await write('owner', 'users/alice/notices/n1', n), OK);
  assert.equal(await get('alice', 'users/alice/notices/n1'), OK);
  assert.equal(await list('alice', 'users/alice/notices'), OK);
  assert.equal(await get('bob', 'users/alice/notices/n1'), DENIED);
  assert.equal(await list('bob', 'users/alice/notices'), DENIED);
  assert.equal(await write('alice', 'users/alice/notices/n1', { kind: 'role' }, 'update'), DENIED);
  assert.equal(await write('bob', 'users/alice/notices/n1', null, 'delete'), DENIED);
  assert.equal(await write('alice', 'users/alice/notices/n1', null, 'delete'), OK);
});

// --- D-194: the documents the apps actually write ------------------------------
// CloudRecords.document (both shells): the payload carries bytes, hlc numbers.
const appRecord = (author) => ({
  type: '1', hlc: { millis: 1790000000123, counter: 7, device: D }, authorUid: author, deviceId: D,
  createdAt: SERVER_TIME,
  payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([0x45, 0x58, 0x50, 0x01]),
    blob: new Uint8Array(Buffer.from('{"paidMinor":120000}')) },
});
test('the record and group documents the apps write are accepted, and only for their author', async () => {
  assert.equal(await write('alice', `users/alice/records/1790000000123-7-${D}`, appRecord('alice')), OK);
  assert.equal(await write('alice', 'groups/g1', group('alice')), OK);
  assert.equal(await write('alice', `groups/g1/records/1790000000123-7-${D}`, appRecord('alice')), OK);
  assert.equal(await list('alice', 'groups', ['memberUids', 'ARRAY_CONTAINS', 'alice']), OK);
  assert.equal(await write('bob', `groups/g1/records/1790000000124-0-${D}`, appRecord('bob')), DENIED);
  assert.equal(await write('alice', `groups/g1/records/1790000000123-7-${D}`, appRecord('alice'), 'set'), DENIED,
    'a second upload of the same record is refused (the apps then read it and count it uploaded)');
});

// --- Functions-only collections ------------------------------------------------
test('usernames and link invites have no client access', async () => {
  assert.equal(await write('alice', 'usernames/alice', { uid: 'alice' }), DENIED);
  assert.equal(await write('owner', 'usernames/alice', { uid: 'alice' }), OK);
  assert.equal(await get('alice', 'usernames/alice'), DENIED);
  assert.equal(await list('alice', 'usernames'), DENIED);
  assert.equal(await write('alice', 'invites/h1', { groupId: 'g1' }), DENIED);
  assert.equal(await write('owner', 'invites/h1', { groupId: 'g1' }), OK);
  assert.equal(await get('alice', 'invites/h1'), DENIED);
});

// --- Storage: group pictures ---------------------------------------------------------
test('a group picture is read and replaced only by members, as a small JPEG', async () => {
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), memberUids: ['alice', 'bob'], createdAt: new Date() }), OK);
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
  assert.equal(await upload('alice', 'groups/g1/picture.jpg', jpeg), OK);
  assert.equal(await download('bob', 'groups/g1/picture.jpg'), OK);
  assert.equal(await download('carol', 'groups/g1/picture.jpg'), DENIED);
  assert.equal(await download(null, 'groups/g1/picture.jpg'), DENIED);
  assert.equal(await upload('carol', 'groups/g1/picture.jpg', jpeg), DENIED);
  assert.equal(await upload('alice', 'groups/g1/picture.jpg', jpeg, 'image/png'), DENIED);
  assert.equal(await upload('alice', 'groups/g1/picture.jpg', new Uint8Array(64001)), DENIED);
  assert.equal(await upload('alice', 'groups/g1/other.jpg', jpeg), DENIED);
});

// --- D-229: the alias mark, and Phase A's round trip ------------------------------
test('D-229: a phone never writes an alias record, in a group or its own log; only shareAliases does', async () => {
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), memberUids: ['alice', 'bob'], createdAt: new Date() }), OK);
  assert.equal(await write('bob', 'groups/g1/records/a1', { ...record('bob'), alias: true }), DENIED);
  assert.equal(await write('bob', 'groups/g1/records/a2', { ...record('bob'), alias: false }), DENIED);
  assert.equal(await write('bob', 'users/bob/records/a3', { ...record('bob'), alias: true }), DENIED, 'a personal one');
  assert.equal(await write('bob', 'groups/g1/records/a4', record('bob')), OK);
});

// --- D-230: the held mark ----------------------------------------------------------
test('D-230: a phone never writes a held record, in a group or its own log; only the Functions do', async () => {
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), memberUids: ['alice', 'bob'], createdAt: new Date() }), OK);
  assert.equal(await write('bob', 'groups/g1/records/h1', { ...record('bob'), held: true }), DENIED);
  assert.equal(await write('bob', 'groups/g1/records/h2', { ...record('bob'), held: false }), DENIED);
  assert.equal(await write('bob', 'users/bob/records/h3', { ...record('bob'), held: true }), DENIED, 'a personal one');
  assert.equal(await write('bob', 'groups/g1/records/h4', record('bob')), OK, 'the same record without the mark');
});

/** The documents `uid` lists under `parent` (a collection path), through the rules. */
async function listDocs(uid, parent) {
  const [collectionId] = parent.split('/').slice(-1);
  const prefix = parent.split('/').slice(0, -1).join('/');
  const r = await fetch(`${FS}/v1/${DOCS}/${prefix}:runQuery`, {
    method: 'POST', headers: { 'content-type': 'application/json', authorization: auth(uid) },
    body: JSON.stringify({ structuredQuery: { from: [{ collectionId }] } }),
  });
  assert.equal(r.status, OK);
  return (await r.json()).filter((x) => x.document).map((x) => x.document);
}

test('D-229 Phase A: a claim naming the place with the other "you", and an expense paid by the place, need no server change', async () => {
  const G = 'c'.repeat(32);
  const X = 'a7'.repeat(16); // al's place in the group
  const Y = 'e7'.repeat(16); // the "you" al's other phone adopted
  assert.equal(await write('owner', `groups/${G}`, { ...group('al'), memberUids: ['al', 'bo'], createdAt: new Date() }), OK);
  const op = (n, type, blob) => ({
    type, hlc: { millis: 1790000000000 + n, counter: 0, device: D }, authorUid: 'al', deviceId: D, createdAt: SERVER_TIME,
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: new Uint8Array(Buffer.from(JSON.stringify(blob))) },
  });
  const claimed = { op: 'claim', group: G, member: X, you: Y, devices: [] };
  const paid = { group: G, payerId: X, amountMinor: 120000, currency: 'INR' };
  assert.equal(await write('al', `groups/${G}/records/1790000000001-0-${D}`, op(1, '3', claimed)), OK);
  assert.equal(await write('al', `groups/${G}/records/1790000000002-0-${D}`, op(2, '1', paid)), OK);
  const docs = await listDocs('bo', `groups/${G}/records`);
  const blobs = docs.map((d) => JSON.parse(Buffer.from(d.fields.payload.mapValue.fields.blob.bytesValue, 'base64').toString('utf8')));
  assert.deepEqual(blobs, [claimed, paid], 'bo reads both, as al\'s phone wrote them');
  assert.deepEqual(docs.map((d) => d.fields.authorUid.stringValue), ['al', 'al']);
});

// --- D-235: a record's shape and size (M1), and its stamp (decision 105) -------------------
const BLOB_MAX = 196608;
const withPayload = (author, over) => ({ ...record(author), payload: { ...record(author).payload, ...over } });
const withHlc = (author, over) => ({ ...record(author), hlc: { ...record(author).hlc, ...over } });

test('D-235 M1: a record\'s payload is the header\'s integers, the nonce and a blob of bytes up to 192 KiB', async () => {
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), memberUids: ['alice', 'bob'], createdAt: new Date() }), OK);
  const at = (n) => `groups/g1/records/r${n}`;
  assert.equal(await write('bob', at(1), withPayload('bob', { blob: new Uint8Array(BLOB_MAX) })), OK, 'the cap itself');
  assert.equal(await write('bob', at(2), withPayload('bob', { blob: new Uint8Array(BLOB_MAX + 1) })), DENIED, 'one byte past it');
  assert.equal(await write('bob', at(3), withPayload('bob', { blob: '{"amountMinor":1}' })), DENIED, 'a blob as text');
  assert.equal(await write('bob', at(4), withPayload('bob', { nonce: 'RED1' })), DENIED, 'a nonce as text');
  assert.equal(await write('bob', at(5), withPayload('bob', { nonce: new Uint8Array(65) })), DENIED, 'a nonce of 65 bytes');
  assert.equal(await write('bob', at(6), withPayload('bob', { extra: 1 })), DENIED, 'an unknown payload field');
  const { blob: _, ...noBlob } = record('bob').payload;
  assert.equal(await write('bob', at(7), { ...record('bob'), payload: noBlob }), DENIED, 'no blob');
  for (const [field, bad] of [['version', 2], ['version', 0], ['version', '1'], ['epoch', 4294967296], ['flags', 256], ['flags', -1]]) {
    assert.equal(await write('bob', at(8), withPayload('bob', { [field]: bad })), DENIED, `${field} ${JSON.stringify(bad)}`);
  }
  assert.equal(await write('bob', at(9), { ...record('bob'), payload: 'x' }), DENIED, 'a payload that is not a map');
  for (const type of ['expense', '1234', '', 3]) {
    assert.equal(await write('bob', at(10), { ...record('bob'), type }), DENIED, `type ${JSON.stringify(type)}`);
  }
  assert.equal(await write('bob', at(11), withPayload('bob', { epoch: 4294967295, flags: 255, nonce: new Uint8Array(64) })), OK,
    'every bound itself');
  assert.equal(await write('bob', 'users/bob/records/p1', withPayload('bob', { blob: 'text' })), DENIED, 'a personal record too');
  assert.equal(await write('bob', 'users/bob/records/p2', withPayload('bob', { blob: new Uint8Array(BLOB_MAX + 1) })), DENIED);
  assert.equal(await write('bob', 'users/bob/records/p3', record('bob')), OK);
});

test('D-235 (decision 105): a record\'s stamp is typed, and never more than 24 hours past the server\'s time', async () => {
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), memberUids: ['alice', 'bob'], createdAt: new Date() }), OK);
  const at = (n) => `groups/g1/records/s${n}`;
  const hour = 3600000;
  assert.equal(await write('bob', at(1), withHlc('bob', { millis: Date.now() + 23 * hour })), OK, '23 hours ahead');
  assert.equal(await write('bob', at(2), withHlc('bob', { millis: Date.now() + 25 * hour })), DENIED, '25 hours ahead');
  assert.equal(await write('bob', at(3), withHlc('bob', { millis: 4000000000000 })), DENIED, 'the year 2096');
  assert.equal(await write('bob', 'users/bob/records/s4', withHlc('bob', { millis: Date.now() + 25 * hour })), DENIED, 'a personal record too');
  assert.equal(await write('bob', at(5), withHlc('bob', { millis: 0 })), OK, 'the oldest stamp');
  for (const [field, bad] of [['millis', -1], ['millis', '1790000000123'], ['counter', 4294967296], ['counter', -1],
    ['device', 'B'.repeat(32)], ['device', 'b'.repeat(31)]]) {
    assert.equal(await write('bob', at(6), withHlc('bob', { [field]: bad })), DENIED, `${field} ${JSON.stringify(bad)}`);
  }
  assert.equal(await write('bob', at(7), withHlc('bob', { extra: 1 })), DENIED, 'an unknown stamp field');
  const { device: _, ...noDevice } = record('bob').hlc;
  assert.equal(await write('bob', at(8), { ...record('bob'), hlc: noDevice }), DENIED, 'no device');
  assert.equal(await write('bob', at(9), withHlc('bob', { counter: 4294967295 })), OK, 'the largest counter');
});

test('D-235 round 2: a phone never writes a group\'s `departed` list (only the purge does)', async () => {
  assert.equal(await write('alice', 'groups/g1', { ...group('alice'), departed: ['bob'] }), DENIED, 'at create');
  assert.equal(await write('alice', 'groups/g1', group('alice')), OK);
  assert.equal(await write('alice', 'groups/g1', { departed: ['bob'] }, 'update'), DENIED, 'at update');
  assert.equal(await write('alice', 'groups/g1', { name: 'Goa 2026' }, 'update'), OK, 'the name still changes');
});

test('D-235 round 3: a phone never writes a group\'s removal count (only removeMember and adoptRemovals do)', async () => {
  assert.equal(await write('alice', 'groups/g1', { ...group('alice'), removals: 0 }), DENIED, 'at create');
  assert.equal(await write('alice', 'groups/g1', group('alice')), OK);
  assert.equal(await write('alice', 'groups/g1', { removals: 0 }, 'update'), DENIED, 'at update');
});

// --- D-258 (G-26, decision 269): a phone signed out from another phone ------
test('D-258: a phone signed out from another phone writes no record and updates nothing but its token removal', async () => {
  const OUT = 'c'.repeat(32); // the phone signed out (signOutDevice: Functions only)
  const IN = 'e'.repeat(32); // the account's other phone, still signed in
  const NONE = 'f'.repeat(32); // a phone whose registration has not landed yet
  const from = (author, deviceId) => ({ ...record(author), deviceId, hlc: { ...record(author).hlc, device: deviceId } });
  const phone = `users/alice/devices/${OUT}`;
  assert.equal(await write('owner', phone, { ...device(), lastSeen: new Date(), fcmToken: 't1', signedOut: true, signedOutAt: new Date() }), OK);
  assert.equal(await write('owner', `users/alice/devices/${IN}`, { ...device(), lastSeen: new Date() }), OK);
  assert.equal(await write('owner', 'groups/g1', { ...group('alice'), createdAt: new Date() }), OK);
  // Records: the signed-out phone's are refused, personal and group; the others' go up.
  assert.equal(await write('alice', 'users/alice/records/r1', from('alice', OUT)), DENIED, 'a personal record from the signed-out phone');
  assert.equal(await write('alice', 'groups/g1/records/r1', from('alice', OUT)), DENIED, 'a group record from the signed-out phone');
  assert.equal(await write('alice', 'users/alice/records/r2', from('alice', IN)), OK, 'the signed-in phone is unaffected');
  assert.equal(await write('alice', 'groups/g1/records/r2', from('alice', IN)), OK);
  assert.equal(await write('alice', 'users/alice/records/r3', from('alice', NONE)), OK, 'no device document: still writes');
  assert.equal(await write('alice', 'groups/g1/records/r3', from('alice', NONE)), OK);
  // A flag that says false is no sign-out.
  assert.equal(await write('owner', `users/alice/devices/${IN}`, { signedOut: false }, 'update'), OK);
  assert.equal(await write('alice', 'users/alice/records/r4', from('alice', IN)), OK, 'signedOut false writes');
  // The signed-out phone's entry: the token removal goes through, nothing else does.
  assert.equal(await write('alice', phone, { name: 'Pixel 9', lastSeen: SERVER_TIME }, 'update'), DENIED, 'a name update');
  assert.equal(await write('alice', phone, { appVersion: '0.5.1', lastSeen: SERVER_TIME }, 'update'), DENIED, 'a version update');
  assert.equal(await write('alice', phone, { fcmToken: 't2', lastSeen: SERVER_TIME }, 'update'), DENIED, 'a new token');
  assert.equal(await write('alice', phone, { fcmToken: DROP, lastSeen: SERVER_TIME }, 'update'), OK, 'the token removal');
  // With no token left, the same update (tokenUpdate(nil): the token field removed, lastSeen stamped) is the
  // lastSeen stamp alone; it still goes through (D-254 M3's tokenRemovalOnly), and the flag stays.
  assert.equal(await write('alice', phone, { fcmToken: DROP, lastSeen: SERVER_TIME }, 'update'), OK, 'again, with no token left');
  assert.equal(await write('alice', phone, { fcmToken: 't3', lastSeen: SERVER_TIME }, 'update'), DENIED, 'a token after it');
  assert.equal(await write('alice', 'users/alice/records/r7', from('alice', OUT)), DENIED, 'still signed out');
  assert.equal(await write('alice', `users/alice/devices/${IN}`, { name: 'Pixel 9', lastSeen: SERVER_TIME }, 'update'), OK,
    'the signed-in phone still updates its entry');
  // Signed in again: the phone deletes its entry and makes it fresh (CloudProfile.register), and writes again.
  assert.equal(await write('alice', phone, null, 'delete'), OK);
  assert.equal(await write('alice', phone, device()), OK);
  assert.equal(await write('alice', 'users/alice/records/r5', from('alice', OUT)), OK, 'a fresh entry writes');
  assert.equal(await write('alice', 'groups/g1/records/r5', from('alice', OUT)), OK);
  // Another account's signed-out phone with the same id changes nothing for alice (the read is under her own uid).
  assert.equal(await write('owner', `users/bob/devices/${IN}`, { ...device(), lastSeen: new Date(), signedOut: true }), OK);
  assert.equal(await write('alice', 'users/alice/records/r6', from('alice', IN)), OK, 'only her own phones count');
});
