// D-210 (C3): roles, removal requests, removing someone and deleting a group,
// called the way the apps call them (the callable protocol over HTTP) against
// the emulators (project demo-skipti). Every refusal asserts its exact status.
// The decisions themselves are tested directly in groups.test.mjs; these
// prove the Functions read, write and refuse what those decisions say.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import * as groups from '../functions/groups.js';
import * as push from '../functions/push.js';

const PROJECT = 'demo-skipti';
const FS = `http://127.0.0.1:${process.env.FIRESTORE_PORT ?? 8180}`;
const FN = `http://127.0.0.1:${process.env.FUNCTIONS_PORT ?? 5001}/${PROJECT}/us-central1`;
const ST = `http://127.0.0.1:${process.env.STORAGE_PORT ?? 9199}`;
const BUCKET = `${PROJECT}.appspot.com`;
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

// Firestore REST values, with maps (former, admins) and bytes (payloads).
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
async function list(path) {
  const r = await fetch(`${FS}/v1/${DOCS}/${path}?pageSize=300`, { headers: { authorization: 'Bearer owner' } });
  return r.status === 200 ? (await r.json()).documents ?? [] : [];
}
const strings = (field) => (field?.arrayValue?.values ?? []).map((v) => v.stringValue);
const idOf = (doc) => doc.name.split('/').pop();

// The group: ow owns it (as its creator), ad is an admin, me and sam are members.
// D-227: the creator's place is the first member of the first create op the
// creator's account wrote (core's create_named_group puts the phone's own "you"
// first); here it happens to equal ow's participant id, which decides nothing.
// The others joined the places the creator added through invites, and the
// server's record of that (invites/{hash}, `usedBy`) is whose place it is. A
// claim op in the log (D-157) is a phone's word and decides nothing here. bo
// never joined.
const G = 'c'.repeat(32);
const D = 'f'.repeat(32); // a device id
const P = { ow: '1'.repeat(32), ad: '2'.repeat(32), me: '3'.repeat(32), sam: '4'.repeat(32), eve: '5'.repeat(32) };
const M = { ow: P.ow, ad: 'b'.repeat(32), me: 'e'.repeat(32), sam: 'd'.repeat(32), bo: '9'.repeat(32) };
const enc = (o) => Buffer.from(JSON.stringify(o)).toString('base64');
const bytes = (o) => new Uint8Array(Buffer.from(JSON.stringify(o)));

/** A record as the phones build it (groups.test.mjs's `rec`). */
const rec = (type, blob, n = 1) => ({
  id: `${2000 + n}-0-${D}`, type, hlc: { millis: 2000 + n, counter: 0, device: D }, deviceId: D,
  payload: { version: 1, epoch: 0, flags: 0, nonce: Buffer.from('RED\x01').toString('base64'), blob: enc(blob) },
});
const removalOf = (member) => rec('3', { op: 'removeMember', group: G, member }, 1);
const movedFrom = (member, n = 2) => rec('6', { group: G, from: member, currency: 'INR', shares: [{ member: M.me, amount: -100 }] }, n);

/** A group operation in the log, written by `uid` as a phone writes it. */
async function op(uid, n, blob, createdAt = new Date()) {
  await seed(`groups/${G}/records/${1000 + n}-0-${D}`, {
    type: '3', hlc: { millis: 1000 + n, counter: 0, device: D }, authorUid: uid, deviceId: D, createdAt,
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes(blob) },
  });
}
/** A claim op a phone wrote into the group's log (D-157); `member` defaults
 *  to the writer's own place (another is a forged claim). */
const claim = (uid, n, member = M[uid]) => op(uid, n, { op: 'claim', group: G, member, you: P[uid], devices: [] });
/** The server's record of `uid` joining place `member`: an accepted invite. */
const joined = (uid, member = M[uid]) => seed(`invites/joined-${uid}-${member.slice(0, 4)}`, {
  groupId: G, memberId: member, memberName: uid, groupName: 'Goa trip', inviterName: 'Ola', createdBy: 'ow',
  createdAt: new Date(), expiresAt: new Date(Date.now() + 3600000), usedBy: uid, usedAt: new Date(),
});
const RECORDS = 2; // create, addMember bo
/** The name each account gives itself (users/{uid}.name): what notices say. */
const NAME = { ow: 'Ola', ad: 'Asha', me: 'Mo', sam: 'Sam', eve: 'Eve' };

beforeEach(async () => {
  const r = await fetch(`${FS}/emulator/v1/${DOCS}`, { method: 'DELETE' });
  assert.equal(r.status, 200, 'the Firestore emulator must be running');
  approvedUids = new Set(); // D-254: the wipe took the approvals too
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam'], createdBy: 'ow', admins: ['ad'], createdAt: new Date() });
  for (const uid of ['ow', 'ad', 'me', 'sam', 'eve']) await seed(`users/${uid}`, { participantId: P[uid], name: NAME[uid] });
  await op('ow', 0, { op: 'create', group: G, name: 'Goa trip', settlementCurrency: 'INR',
    members: [{ id: M.ow, name: 'Ola' }, { id: M.ad, name: 'Asha' }, { id: M.me, name: 'Mo' }, { id: M.sam, name: 'Sam' }] });
  await op('ow', 10, { op: 'addMember', group: G, member: { id: M.bo, name: 'Bo' } });
  for (const uid of ['ad', 'me', 'sam']) await joined(uid);
});

/** The place each account holds (bo's, once bo joins it as '7'…). */
const PLACE = { [P.ow]: M.ow, [P.ad]: M.ad, [P.me]: M.me, [P.sam]: M.sam, ['7'.repeat(32)]: M.bo };
const roles = (over = {}) => ({
  groupId: G, byName: 'Asha', groupName: 'Goa trip', ...('you' in over ? { member: PLACE[over.you] ?? M.bo } : {}), ...over,
});
/** groupRoles' D-210 fields alone (D-227 added `places` beside them, asserted on its own). */
const roleIds = ({ result: { places: _, ...ids } }) => ids;
const notices = async (uid) => (await list(`users/${uid}/notices`)).map((d) => ({ id: idOf(d), ...d.fields }));
const kinds = async (uid) => (await notices(uid)).map((n) => n.kind.stringValue).sort();

// ---- groupRoles ----

test('groupRoles: any member sees the owner and the admins as participant ids; nobody else', async () => {
  for (const uid of ['ow', 'ad', 'me']) {
    assert.deepEqual(roleIds(await call('groupRoles', uid, { groupId: G })), { owner: P.ow, admins: [P.ad] }, uid);
  }
  assert.deepEqual(await call('groupRoles', 'eve', { groupId: G }), { error: 'PERMISSION_DENIED' });
  assert.deepEqual(await call('groupRoles', null, { groupId: G }), { error: 'UNAUTHENTICATED' });
  assert.deepEqual(await call('groupRoles', 'me', { groupId: 'x' }), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('groupRoles', 'me', { groupId: 'e'.repeat(32) }), { error: 'NOT_FOUND' });
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam'], createdBy: 'ad', owner: 'me', admins: [] });
  assert.deepEqual(roleIds(await call('groupRoles', 'sam', { groupId: G })), { owner: P.me, admins: [] }, '`owner` over `createdBy`');
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam'], createdBy: 'ad' });
  assert.deepEqual(roleIds(await call('groupRoles', 'sam', { groupId: G })), { owner: P.ad, admins: [] }, 'an old group: its creator');
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam'], createdBy: 'gone', admins: ['me'] });
  assert.deepEqual(roleIds(await call('groupRoles', 'sam', { groupId: G })), { owner: P.me, admins: [] },
    'a creator purged before D-210: the first admin holds the seat');
});

// ---- setAdmin ----

test('setAdmin: the owner or an admin makes an admin; only the owner takes it away', async () => {
  assert.deepEqual(await call('setAdmin', 'me', roles({ you: P.sam, on: true })), { error: 'PERMISSION_DENIED' });
  assert.deepEqual((await call('setAdmin', 'ad', roles({ you: P.me, on: true }))).result, { admins: [P.ad, P.me] });
  assert.deepEqual(strings((await read(`groups/${G}`)).admins), ['ad', 'me']);
  const [role] = await notices('me');
  assert.equal(role.kind.stringValue, 'role');
  assert.equal(role.role.stringValue, 'admin');
  assert.equal(role.byName.stringValue, 'Asha');
  assert.equal(role.groupId.stringValue, G);
  assert.ok(role.at.timestampValue);
  assert.deepEqual(await call('setAdmin', 'ad', roles({ you: P.me, on: false })), { error: 'PERMISSION_DENIED' }, 'an admin cannot take it away');
  assert.deepEqual((await call('setAdmin', 'ow', roles({ you: P.me, on: false }))).result, { admins: [P.ad] });
  assert.deepEqual(strings((await read(`groups/${G}`)).admins), ['ad']);
  assert.deepEqual(await kinds('me'), ['role'], 'taking it away tells nobody');
});

test('setAdmin refuses the owner, someone already an admin, a stranger and bad input', async () => {
  assert.deepEqual(await call('setAdmin', 'ad', roles({ you: P.ow, on: true })), { error: 'PERMISSION_DENIED' }, 'the owner');
  assert.deepEqual(await call('setAdmin', 'ow', roles({ you: P.ad, on: true })), { error: 'FAILED_PRECONDITION' }, 'already an admin');
  assert.deepEqual(await call('setAdmin', 'ow', roles({ you: P.me, on: false })), { error: 'FAILED_PRECONDITION' }, 'not an admin');
  assert.deepEqual(await call('setAdmin', 'ow', roles({ you: P.eve, on: true })), { error: 'NOT_FOUND' }, 'not a member');
  assert.deepEqual(await call('setAdmin', 'ow', roles({ you: '6'.repeat(32), on: true })), { error: 'NOT_FOUND' }, 'no such account');
  assert.deepEqual(await call('setAdmin', 'eve', roles({ you: P.me, on: true })), { error: 'PERMISSION_DENIED' }, 'a stranger');
  assert.deepEqual(await call('setAdmin', 'ow', roles({ you: 'x', on: true })), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('setAdmin', 'ow', roles({ you: P.me, on: 'yes' })), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('setAdmin', 'ow', roles({ you: P.me, on: true, member: 'x' })), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('setAdmin', 'ow', { groupId: G, groupName: 'Goa trip', you: P.me, on: true }), { error: 'INVALID_ARGUMENT' }, 'no place');
  assert.deepEqual(strings((await read(`groups/${G}`)).admins), ['ad'], 'nothing changed');
});

test('setAdmin: a new admin is shown the open requests', async () => {
  assert.ok((await call('requestRemoval', 'me', { groupId: G, member: M.sam, you: P.sam, memberName: 'Sam', groupName: 'Goa trip', askerName: 'Mo' })).result);
  await seed('users/bo', { participantId: '7'.repeat(32) });
  await joined('bo', M.bo);
  assert.deepEqual(await notices('ow').then((n) => n.map((x) => x.id)), [`request-${G}-${M.sam}`]);
  await call('setAdmin', 'ow', roles({ you: P.me, on: true }));
  assert.deepEqual(await kinds('me'), ['request', 'role'], 'me already had it as the asker; now also the role');
  // A plain member made admin gets the request notice too.
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam', 'bo'], createdBy: 'ow', admins: ['ad', 'me'] });
  await call('setAdmin', 'ow', roles({ you: '7'.repeat(32), on: true }));
  const bo = await notices('bo');
  assert.deepEqual(bo.map((n) => n.kind.stringValue).sort(), ['request', 'role']);
  const req = bo.find((n) => n.kind.stringValue === 'request');
  assert.equal(req.id, `request-${G}-${M.sam}`);
  assert.equal(req.byName.stringValue, 'Mo');
  assert.equal(req.memberName.stringValue, 'Sam');
  assert.equal(req.memberId.stringValue, M.sam);
});

// ---- makeOwner ----

test('makeOwner: only the owner hands over; the old owner becomes an admin', async () => {
  assert.deepEqual(await call('makeOwner', 'ad', roles({ you: P.me })), { error: 'PERMISSION_DENIED' });
  assert.deepEqual(await call('makeOwner', 'ow', roles({ you: P.ow })), { error: 'PERMISSION_DENIED' }, 'yourself');
  assert.deepEqual(await call('makeOwner', 'ow', roles({ you: P.eve })), { error: 'NOT_FOUND' });
  assert.deepEqual((await call('makeOwner', 'ow', roles({ you: P.ad }))).result, { owner: P.ad });
  let g = await read(`groups/${G}`);
  assert.equal(g.owner.stringValue, 'ad');
  assert.deepEqual(strings(g.admins), ['ow'], 'the new owner leaves the admins; the old one joins');
  assert.deepEqual(roleIds(await call('groupRoles', 'sam', { groupId: G })), { owner: P.ad, admins: [P.ow] });
  assert.deepEqual(await call('makeOwner', 'ow', roles({ you: P.me })), { error: 'PERMISSION_DENIED' }, 'not the owner any more');
  assert.deepEqual((await call('makeOwner', 'ad', roles({ you: P.me }))).result, { owner: P.me });
  g = await read(`groups/${G}`);
  assert.equal(g.owner.stringValue, 'me');
  assert.deepEqual(strings(g.admins), ['ow', 'ad']);
  const role = (await notices('me')).find((n) => n.kind.stringValue === 'role');
  assert.equal(role.role.stringValue, 'owner');
});

// ---- requestRemoval ----

const ask = (over = {}) => ({ groupId: G, member: M.sam, you: P.sam, memberName: 'Sam', groupName: 'Goa trip', askerName: 'Mo', ...over });

test('requestRemoval: a member asks; the asker, the owner, the admins and the person see it', async () => {
  assert.deepEqual(await call('requestRemoval', 'eve', ask()), { error: 'PERMISSION_DENIED' }, 'a stranger');
  assert.deepEqual(await call('requestRemoval', 'me', ask({ member: M.ow, you: P.ow, memberName: 'Ola' })), { error: 'PERMISSION_DENIED' }, 'the owner');
  assert.deepEqual((await call('requestRemoval', 'me', ask())).result, { requested: true });
  const r = await read(`groups/${G}/requests/${M.sam}`);
  assert.equal(r.member.stringValue, M.sam);
  assert.equal(r.you.stringValue, P.sam);
  assert.equal(r.memberName.stringValue, 'Sam');
  assert.equal(r.askerUid.stringValue, 'me');
  assert.equal(r.askerName.stringValue, 'Mo');
  assert.ok(r.at.timestampValue);
  for (const uid of ['ow', 'ad', 'me', 'sam']) {
    const n = await notices(uid);
    assert.deepEqual(n.map((x) => x.id), [`request-${G}-${M.sam}`], uid);
    assert.equal(n[0].kind.stringValue, 'request');
    assert.equal(n[0].byName.stringValue, 'Mo');
    assert.equal(n[0].memberId.stringValue, M.sam);
    assert.equal(n[0].memberName.stringValue, 'Sam');
    assert.equal(n[0].groupName.stringValue, 'Goa trip');
  }
  assert.deepEqual(await call('requestRemoval', 'ad', ask()), { error: 'ALREADY_EXISTS' }, 'one open request per person');
});

test('requestRemoval: yourself, and someone with no account', async () => {
  assert.deepEqual((await call('requestRemoval', 'me', ask({ member: M.me, you: P.me, memberName: 'Mo' }))).result, { requested: true });
  assert.deepEqual((await call('requestRemoval', 'sam', ask({ member: M.bo, you: null, memberName: 'Bo' }))).result, { requested: true });
  assert.equal((await read(`groups/${G}/requests/${M.bo}`)).you.nullValue, null);
  assert.deepEqual((await notices('ow')).map((n) => n.id).sort(), [`request-${G}-${M.bo}`, `request-${G}-${M.me}`].sort());
});

test('requestRemoval: the person and the member id must be the same person', async () => {
  assert.deepEqual(await call('requestRemoval', 'me', ask({ member: M.ow })), { error: 'NOT_FOUND' }, 'the owner\'s place, under sam\'s account');
  assert.deepEqual(await call('requestRemoval', 'me', ask({ you: null, member: M.ow })), { error: 'PERMISSION_DENIED' },
    'the owner, passed off as someone who never joined: the log says whose place it is');
  assert.deepEqual(await call('requestRemoval', 'me', ask({ you: P.eve })), { error: 'NOT_FOUND' }, 'not a member');
  assert.deepEqual(await call('requestRemoval', 'me', ask({ member: 'x' })), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('requestRemoval', 'me', ask({ memberName: '' })), { error: 'INVALID_ARGUMENT' });
  assert.equal(await read(`groups/${G}/requests/${M.ow}`), null);
});

// ---- declineRemoval ----

const decline = (over = {}) => ({ groupId: G, member: M.sam, byName: 'Asha', groupName: 'Goa trip', ...over });

test('declineRemoval: the owner or an admin keeps the person; the request and its notices go', async () => {
  await call('requestRemoval', 'me', ask());
  assert.deepEqual(await call('declineRemoval', 'me', decline()), { error: 'PERMISSION_DENIED' });
  assert.deepEqual(await call('declineRemoval', 'ad', decline({ member: M.bo })), { error: 'NOT_FOUND' }, 'no such request');
  assert.deepEqual((await call('declineRemoval', 'ad', decline())).result, { declined: true });
  assert.equal(await read(`groups/${G}/requests/${M.sam}`), null);
  for (const uid of ['ow', 'ad']) assert.deepEqual(await notices(uid), [], uid);
  const asker = await notices('me');
  assert.deepEqual(asker.map((n) => n.kind.stringValue), ['declined']);
  assert.equal(asker[0].byName.stringValue, 'Asha');
  assert.equal(asker[0].memberName.stringValue, 'Sam');
  assert.deepEqual(await kinds('sam'), ['declined']);
});

test('declineRemoval: a request about an admin is the owner\'s to decline', async () => {
  await call('requestRemoval', 'me', ask({ member: M.ad, you: P.ad, memberName: 'Asha' }));
  await call('setAdmin', 'ow', roles({ you: P.sam, on: true }));
  assert.deepEqual(await call('declineRemoval', 'sam', decline({ member: M.ad })), { error: 'PERMISSION_DENIED' });
  assert.deepEqual((await call('declineRemoval', 'ow', decline({ member: M.ad }))).result, { declined: true });
});

// ---- removeMember ----

const remove = (who, over = {}) => ({
  groupId: G, member: M[who], you: P[who], memberName: who, groupName: 'Goa trip', byName: 'Asha',
  records: [movedFrom(M[who]), removalOf(M[who])], ...over,
});

test('removeMember: the owner or an admin; never the owner; an admin only by the owner', async () => {
  assert.deepEqual(await call('removeMember', 'me', remove('sam')), { error: 'PERMISSION_DENIED' });
  assert.deepEqual(await call('removeMember', 'eve', remove('sam')), { error: 'PERMISSION_DENIED' });
  assert.deepEqual(await call('removeMember', 'ad', remove('ow')), { error: 'PERMISSION_DENIED' }, 'nobody removes the owner');
  await call('setAdmin', 'ow', roles({ you: P.me, on: true }));
  assert.deepEqual(await call('removeMember', 'ad', remove('me')), { error: 'PERMISSION_DENIED' }, 'an admin never removes an admin');
  assert.deepEqual(await call('removeMember', 'ad', remove('sam', { you: P.eve })), { error: 'NOT_FOUND' }, 'not a member');
  assert.deepEqual(await call('removeMember', 'ad', remove('sam', { member: M.ow, records: [removalOf(M.ow)] })), { error: 'NOT_FOUND' },
    'sam\'s account, the owner\'s place');
  assert.deepEqual(await call('removeMember', 'ad', remove('ow', { you: null })), { error: 'PERMISSION_DENIED' },
    'the owner, passed off as someone who never joined: the log says whose place it is');
  assert.equal((await list(`groups/${G}/records`)).length, RECORDS, 'nothing written');
  assert.deepEqual((await call('removeMember', 'ow', remove('me'))).result, { removed: true }, 'the owner removes an admin');
  const g = await read(`groups/${G}`);
  assert.deepEqual(strings(g.memberUids), ['ow', 'ad', 'sam']);
  assert.deepEqual(strings(g.admins), ['ad'], 'and they are no longer an admin');
});

test('removeMember refuses records that are not exactly this removal', async () => {
  for (const records of [[], [movedFrom(M.sam)], [removalOf(M.me)], [removalOf(M.sam), removalOf(M.sam)],
    [{ ...removalOf(M.sam), id: 'x' }], 'x', null]) {
    assert.deepEqual(await call('removeMember', 'ad', remove('sam', { records })), { error: 'INVALID_ARGUMENT' }, JSON.stringify(records));
  }
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam']);
});

test('removeMember: the records, the membership and the former entry change together', async () => {
  await call('requestRemoval', 'me', ask());
  await seed('invites/h-unused', { groupId: G, memberId: M.sam, usedBy: null });
  await seed('invites/h-used', { groupId: G, memberId: M.sam, usedBy: 'sam' });
  await seed('invites/h-other', { groupId: G, memberId: M.bo, usedBy: null });
  await seed('users/sam/invites/i1', { groupId: G, memberId: M.sam, token: 't' });
  await seed('users/sam/invites/i2', { groupId: 'e'.repeat(32), memberId: M.sam, token: 'u' });
  const records = [movedFrom(M.sam), removalOf(M.sam)];
  assert.deepEqual((await call('removeMember', 'ad', remove('sam', { records }))).result, { removed: true });

  const g = await read(`groups/${G}`);
  assert.deepEqual(strings(g.memberUids), ['ow', 'ad', 'me']);
  const former = g.former.mapValue.fields.sam.mapValue.fields;
  assert.equal(former.by.stringValue, 'ad');
  for (const r of records) {
    const doc = await read(`groups/${G}/records/${r.id}`);
    assert.equal(doc.removal.booleanValue, true, r.id);
    assert.equal(doc.authorUid.stringValue, 'ad');
    assert.equal(doc.type.stringValue, r.type, 'a string, as the phones write and read it');
    assert.equal(doc.deviceId.stringValue, D);
    assert.equal(doc.hlc.mapValue.fields.millis.integerValue, String(r.hlc.millis));
    assert.equal(doc.payload.mapValue.fields.blob.bytesValue, r.payload.blob, 'Bytes');
    assert.equal(doc.payload.mapValue.fields.nonce.bytesValue, r.payload.nonce);
    assert.equal(doc.payload.mapValue.fields.version.integerValue, '1');
    assert.equal(doc.createdAt.timestampValue, former.at.timestampValue, 'written at the moment of removal');
  }
  assert.equal(await read(`groups/${G}/requests/${M.sam}`), null, 'the open request is closed');
  for (const uid of ['ow', 'ad', 'me']) assert.deepEqual(await notices(uid), [], uid);
  const sam = await notices('sam');
  assert.deepEqual(sam.map((n) => n.kind.stringValue), ['removed']);
  assert.equal(sam[0].removedAt.timestampValue, former.at.timestampValue);
  assert.equal(sam[0].byName.stringValue, 'Asha');
  assert.equal(await read('invites/h-unused'), null);
  assert.ok(await read('invites/h-used'), 'a used invite is history');
  assert.ok(await read('invites/h-other'), 'another person\'s invite stays');
  assert.equal(await read('users/sam/invites/i1'), null);
  assert.ok(await read('users/sam/invites/i2'), 'another group\'s invite stays');
  assert.deepEqual(await call('removeMember', 'ad', remove('sam')), { error: 'NOT_FOUND' }, 'gone already');
  assert.deepEqual(await call('groupRoles', 'sam', { groupId: G }), { error: 'PERMISSION_DENIED' });
});

test('removeMember: the same records twice are refused, and nothing changes', async () => {
  await seed(`groups/${G}/records/${removalOf(M.sam).id}`, { type: '1', authorUid: 'me' });
  assert.deepEqual(await call('removeMember', 'ad', remove('sam')), { error: 'ALREADY_EXISTS' });
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam']);
  assert.equal(await read(`groups/${G}/records/${movedFrom(M.sam).id}`), null, 'all or nothing');
});

test('removeMember: someone who never joined leaves the history; membership is untouched', async () => {
  await call('requestRemoval', 'me', ask({ member: M.bo, you: null, memberName: 'Bo' }));
  await seed('invites/h-bo', { groupId: G, memberId: M.bo, usedBy: null });
  const records = [removalOf(M.bo)];
  assert.deepEqual((await call('removeMember', 'ad', remove('bo', { you: null, records }))).result, { removed: true });
  const doc = await read(`groups/${G}/records/${records[0].id}`);
  assert.equal(doc.removal.booleanValue, true);
  const g = await read(`groups/${G}`);
  assert.deepEqual(strings(g.memberUids), ['ow', 'ad', 'me', 'sam']);
  assert.equal(g.former, undefined);
  assert.equal(await read(`groups/${G}/requests/${M.bo}`), null);
  assert.deepEqual(await notices('ow'), []);
  assert.equal(await read('invites/h-bo'), null);
});

// ---- deleteGroup ----

const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
async function upload(uid, path) {
  await approve(uid);
  const r = await fetch(`${ST}/v0/b/${BUCKET}/o?name=${encodeURIComponent(path)}`, {
    method: 'POST', headers: { 'content-type': 'image/jpeg', authorization: `Firebase ${idToken(uid)}` }, body: jpeg,
  });
  return r.status;
}
async function stored(path) {
  const r = await fetch(`${ST}/storage/v1/b/${BUCKET}/o/${encodeURIComponent(path)}`, { headers: { authorization: 'Bearer owner' } });
  await r.arrayBuffer();
  return r.status;
}

test('deleteGroup: the owner alone; everything goes, every member is told, the owner\'s account too', async () => {
  await call('requestRemoval', 'me', ask());
  assert.equal(await upload('ow', `groups/${G}/picture.jpg`), 200);
  assert.equal(await stored(`groups/${G}/picture.jpg`), 200);
  assert.deepEqual(await call('deleteGroup', 'ad', { groupId: G, groupName: 'Goa trip', byName: 'Asha' }), { error: 'PERMISSION_DENIED' });
  assert.deepEqual(await call('deleteGroup', 'eve', { groupId: G, groupName: 'Goa trip', byName: 'Eve' }), { error: 'PERMISSION_DENIED' });
  assert.ok(await read(`groups/${G}`));
  assert.deepEqual((await call('deleteGroup', 'ow', { groupId: G, groupName: 'Goa trip', byName: 'Ola' })).result, { deleted: true });
  assert.equal(await read(`groups/${G}`), null);
  assert.deepEqual(await list(`groups/${G}/records`), []);
  assert.deepEqual(await list(`groups/${G}/requests`), []);
  assert.equal(await stored(`groups/${G}/picture.jpg`), 404);
  // D-252 (decision 218): the other members are still told, and so is the owner's own account (the request
  // notice is cleared): its other phones learn of the delete only from this notice. `byUid` names the deleter.
  for (const uid of ['ad', 'me', 'sam', 'ow']) {
    const n = await notices(uid);
    assert.deepEqual(n.map((x) => x.kind.stringValue), ['deleted'], uid);
    assert.equal(n[0].byName.stringValue, 'Ola');
    assert.equal(n[0].groupName.stringValue, 'Goa trip');
    assert.equal(n[0].groupId.stringValue, G);
    assert.equal(n[0].byUid.stringValue, 'ow', `${uid}: whose delete it was`);
  }
  assert.deepEqual(await call('deleteGroup', 'ow', { groupId: G, groupName: 'Goa trip', byName: 'Ola' }), { error: 'NOT_FOUND' });
  assert.equal((await notices('ow')).length, 1, 'a second try tells nobody again');
});

test('D-252: deleteGroup pushes to the other members only; the deleting account is told quietly (stored, no push)', () => {
  const audience = groups.deletionAudience(['ow', 'ad', 'me', 'sam'], 'ow');
  assert.deepEqual(audience.told, ['ow', 'ad', 'me', 'sam'], 'every member has the notice, the deleter included');
  assert.deepEqual(audience.quiet, ['ow']);
  // What tell pushes to (push.loud, the one filter tell uses): never the deleter's phones.
  assert.deepEqual(push.loud(audience.told, audience.quiet), ['ad', 'me', 'sam']);
  assert.ok(!push.loud(audience.told, audience.quiet).includes('ow'), 'no push to the caller');
  // An owner missing from memberUids (an old group) is still told, still quietly; a member listed twice, once.
  assert.deepEqual(groups.deletionAudience(['me', 'me'], 'ow'), { told: ['me', 'ow'], quiet: ['ow'] });
  assert.deepEqual(push.loud(['me', 'ow'], ['ow']), ['me']);
  // index.js wires exactly this: deleteGroup tells the audience with its quiet list, and tell pushes through push.loud.
  const source = readFileSync(new URL('../functions/index.js', import.meta.url), 'utf8');
  const del = source.slice(source.indexOf('export const deleteGroup'), source.indexOf('export const adoptRemovals'));
  assert.match(del, /const audience = groups\.deletionAudience\(members, uid\);/);
  assert.match(del, /tell\('deleteGroup', audience\.told, \{ kind: 'deleted', groupId, groupName, byName, byUid: uid \}, \{ quiet: audience\.quiet \}\)/);
  assert.equal((del.match(/tell\(/g) ?? []).length, 1, 'one notice step');
  const tellBody = source.slice(source.indexOf('async function tell('), source.indexOf('async function forgetRequest('));
  assert.match(tellBody, /const loud = push\.loud\(to, quiet\);/);
  assert.match(tellBody, /loud\.map\(async \(u\) =>/, 'only the loud accounts\' phones are read for a push');
});

test('deleteGroup without a picture, in an old group (its creator owns it)', async () => {
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'me'], createdBy: 'me' });
  assert.deepEqual(await call('deleteGroup', 'ow', { groupId: G, groupName: 'Goa trip', byName: 'Ola' }), { error: 'PERMISSION_DENIED' });
  assert.deepEqual((await call('deleteGroup', 'me', { groupId: G, groupName: 'Goa trip', byName: 'Mo' })).result, { deleted: true });
  assert.equal(await read(`groups/${G}`), null);
});

// ---- acceptInvite after a removal ----

test('a removed person invited again (to a new place) is a member again, and no longer former', async () => {
  await call('removeMember', 'ad', remove('sam'));
  assert.ok((await read(`groups/${G}`)).former.mapValue.fields.sam);
  // D-230 decision 64: an older link to the removed place itself is refused, unspent.
  const token = 'T'.repeat(43);
  const hash = createHash('sha256').update(token).digest('hex');
  await seed(`invites/${hash}`, { groupId: G, memberId: M.sam, memberName: 'Sam', groupName: 'Goa trip', inviterName: 'Asha',
    createdBy: 'ad', createdAt: new Date(), expiresAt: new Date(Date.now() + 3600000), usedBy: null });
  assert.deepEqual(await call('acceptInvite', 'sam', { token }), { error: 'FAILED_PRECONDITION' }, 'the removed place');
  assert.equal((await read(`invites/${hash}`)).usedBy.nullValue, null, 'unspent');
  // The shells re-invite to a new place (D-210).
  const K = '6c'.repeat(16);
  await op('ad', 11, { op: 'addMember', group: G, member: { id: K, name: 'Sam' } });
  assert.ok((await call('acceptInvite', 'sam', { token: await invite(K, 'U'.repeat(43)) })).result);
  const g = await read(`groups/${G}`);
  assert.deepEqual(strings(g.memberUids), ['ow', 'ad', 'me', 'sam']);
  assert.equal(g.former.mapValue.fields?.sam, undefined);
  // A member again (through the invite they accepted), so they can be removed again.
  assert.deepEqual((await call('removeMember', 'ad', remove('sam', { member: K, records: [removalAt(K, 5)] }))).result, { removed: true });
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me']);
});

// ---- D-210 review, fix 1: whose place it is ----

const removalAt = (member, n) => rec('3', { op: 'removeMember', group: G, member }, n);

test('the creator\'s place is the creator: nobody removes the owner, nor asks to', async () => {
  assert.deepEqual(await call('removeMember', 'ad', remove('ow', { you: null })), { error: 'PERMISSION_DENIED' }, 'you: null');
  assert.deepEqual(await call('removeMember', 'ad', remove('ow')), { error: 'PERMISSION_DENIED' }, 'you: the owner');
  assert.deepEqual(await call('requestRemoval', 'me', ask({ member: M.ow, you: null, memberName: 'Ola' })), { error: 'PERMISSION_DENIED' });
  assert.deepEqual(await call('requestRemoval', 'me', ask({ member: M.ow, you: P.ow, memberName: 'Ola' })), { error: 'PERMISSION_DENIED' });
  assert.equal(await read(`groups/${G}/requests/${M.ow}`), null);
  assert.equal((await list(`groups/${G}/records`)).length, RECORDS, 'nothing written');
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam']);
});

test('after a hand-over the creator is an admin: an admin never removes them; the new owner does', async () => {
  assert.ok((await call('makeOwner', 'ow', roles({ you: P.me }))).result);
  assert.deepEqual(await call('removeMember', 'ad', remove('ow', { you: null })), { error: 'PERMISSION_DENIED' }, 'you: null');
  assert.deepEqual(await call('removeMember', 'ad', remove('ow')), { error: 'PERMISSION_DENIED' }, 'you: the old owner');
  assert.deepEqual((await call('removeMember', 'me', remove('ow', { you: null }))).result, { removed: true });
  const g = await read(`groups/${G}`);
  assert.deepEqual(strings(g.memberUids), ['ad', 'me', 'sam']);
  assert.deepEqual(strings(g.admins), ['ad']);
  assert.equal(g.former.mapValue.fields.ow.mapValue.fields.by.stringValue, 'me');
});

// ---- D-210 review, fix 3: a place's account is the server's record ----

test('a forged claim on another\'s place blocks nothing, and never redirects a removal', async () => {
  await claim('me', -1, M.sam); // me's phone claims sam's place, earlier than anything of sam's
  await claim('sam', 1);
  assert.deepEqual(await call('removeMember', 'ad', remove('sam', { you: P.me })), { error: 'NOT_FOUND' }, 'the forger is not that place');
  assert.deepEqual(await call('requestRemoval', 'ow', ask({ you: P.me })), { error: 'NOT_FOUND' });
  // A claim on the creator's place never makes it the claimant's either.
  await claim('sam', 30, M.ow);
  assert.deepEqual(await call('removeMember', 'ad', remove('ow', { you: P.sam })), { error: 'NOT_FOUND' });
  assert.deepEqual(await call('removeMember', 'ad', remove('ow', { you: null })), { error: 'PERMISSION_DENIED' }, 'still the owner');
  assert.equal((await list(`groups/${G}/records`)).length, RECORDS + 3, 'nothing written');
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam']);
  // The owner removes the victim, and only the victim.
  assert.deepEqual((await call('removeMember', 'ow', remove('sam'))).result, { removed: true });
  const g = await read(`groups/${G}`);
  assert.deepEqual(strings(g.memberUids), ['ow', 'ad', 'me']);
  assert.ok(g.former.mapValue.fields.sam);
  assert.equal(g.former.mapValue.fields.me, undefined);
});

test('two members claiming each other\'s places make nobody immune', async () => {
  await claim('me', 31, M.sam);
  await claim('sam', 32, M.me);
  await claim('me', 33);
  await claim('sam', 34);
  assert.deepEqual((await call('removeMember', 'ow', remove('sam', { records: [removalAt(M.sam, 1)] }))).result, { removed: true });
  assert.deepEqual((await call('removeMember', 'ow', remove('me', { you: null, records: [removalAt(M.me, 2)] }))).result, { removed: true });
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad']);
});

test('a joined place resolves to the account its accepted invite records, with no claim in the log', async () => {
  await seed('invites/h-spare', { groupId: G, memberId: M.sam, usedBy: null });
  assert.deepEqual((await call('requestRemoval', 'me', ask({ you: null }))).result, { requested: true });
  assert.equal((await read(`groups/${G}/requests/${M.sam}`)).you.stringValue, P.sam, 'the account\'s own id, from the server');
  assert.deepEqual(await kinds('sam'), ['request'], 'the person is told');
  assert.deepEqual((await call('removeMember', 'ad', remove('sam', { you: null }))).result, { removed: true });
  const g = await read(`groups/${G}`);
  assert.deepEqual(strings(g.memberUids), ['ow', 'ad', 'me']);
  assert.equal(g.former.mapValue.fields.sam.mapValue.fields.by.stringValue, 'ad');
});

test('an accepted invite whose account left is history: the place invited again is the new account\'s', async () => {
  // D-230 decision 64 (A9): a removed place is never invited again, so "left" here is sam's account deleted
  // (account-store drops it from memberUids, no removal written): its used invite names a non-member, which is history.
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me'], createdBy: 'ow', admins: ['ad'], createdAt: new Date() });
  await seed('users/kai', { participantId: '6'.repeat(32), name: 'kai' });
  const token = 'K'.repeat(43);
  await seed(`invites/${createHash('sha256').update(token).digest('hex')}`, { groupId: G, memberId: M.sam, memberName: 'Sam',
    groupName: 'Goa trip', inviterName: 'Ola', createdBy: 'ow', createdAt: new Date(), expiresAt: new Date(Date.now() + 3600000), usedBy: null });
  assert.ok((await call('acceptInvite', 'kai', { token })).result, 'sam left, so the place is free');
  assert.deepEqual(await call('removeMember', 'ow', remove('sam', { records: [removalAt(M.sam, 2)] })), { error: 'NOT_FOUND' },
    'sam is not that place any more');
  assert.deepEqual((await call('requestRemoval', 'me', ask({ you: null }))).result, { requested: true });
  assert.deepEqual(await kinds('kai'), ['request']);
  assert.deepEqual((await call('removeMember', 'ow', remove('sam', { you: '6'.repeat(32), records: [removalAt(M.sam, 3)] }))).result,
    { removed: true });
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me']);
});

test('a second account never joins a place a member already holds', async () => {
  await seed('users/x2', { participantId: '6'.repeat(32), name: 'x2' });
  for (const member of [M.sam, M.ow]) {
    const token = (member === M.ow ? 'O' : 'S').repeat(43);
    await seed(`invites/${createHash('sha256').update(token).digest('hex')}`, { groupId: G, memberId: member, memberName: 'Sam',
      groupName: 'Goa trip', inviterName: 'Mo', createdBy: 'me', createdAt: new Date(), expiresAt: new Date(Date.now() + 3600000), usedBy: null });
    assert.deepEqual(await call('acceptInvite', 'x2', { token }), { error: 'FAILED_PRECONDITION' }, member);
  }
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam']);
  assert.deepEqual((await call('removeMember', 'ow', remove('sam'))).result, { removed: true }, 'sam is still removable');
});

// ---- D-218: the invite Functions refuse a place a member already holds ----

const HELD_ERR = { error: 'ALREADY_EXISTS' };
/** An invite as the apps send it, for place `member`, as someone in the group. */
const link = (member, over = {}) => ({ groupId: G, memberId: member, memberName: 'Bo', groupName: 'Goa trip', inviterName: 'Mo', ...over });
/** The documents of a collection, by id, for a before and after comparison. */
const snapshotOf = async (path) => Object.fromEntries((await list(path)).map((d) => [idOf(d), JSON.stringify(d.fields)]));

test('D-218: invites for a place a member holds are refused at creation, by all three Functions', async () => {
  // A findable outsider for inviteUser, and a person `me` shares a second group with for inviteKnown.
  await seed('users/out', { name: 'Out', findable: true, participantId: '8'.repeat(32) });
  const V = 'e'.repeat(32);
  const PK = '7'.repeat(32);
  await seed(`groups/${V}`, { name: 'Flat', memberUids: ['me', 'kit'], createdBy: 'me', createdAt: new Date() });
  await seed('users/kit', { name: 'Kit', findable: false, participantId: PK });
  const invitesBefore = await snapshotOf('invites');
  const limitsBefore = await read('limits/me');

  for (const [who, member] of [['sam', M.sam], ['the creator', M.ow], ['yourself', M.me]]) {
    assert.deepEqual(await call('createInvite', 'me', link(member)), HELD_ERR, `createInvite for ${who}'s place`);
  }
  assert.deepEqual(await call('inviteUser', 'me', { ...link(M.sam), targetUid: 'out' }), HELD_ERR, 'inviteUser');
  assert.deepEqual(await call('inviteKnown', 'me', { ...link(M.sam), viaGroupId: V, participantId: PK }), HELD_ERR, 'inviteKnown');

  assert.deepEqual(await snapshotOf('invites'), invitesBefore, 'no link invite was written');
  assert.equal((await list('users/out/invites')).length, 0, 'inviteUser delivered nothing');
  assert.equal((await list('users/kit/invites')).length, 0, 'inviteKnown delivered nothing');
  assert.deepEqual(await read('limits/me'), limitsBefore, 'no daily quota was spent');
});

test('D-218: a place nobody holds can still be invited, more than once; D-230: a removed person\'s place is not', async () => {
  assert.ok((await call('createInvite', 'me', link(M.bo))).result.token, 'bo\'s place, once');
  assert.ok((await call('createInvite', 'me', link(M.bo))).result.token, 'an open invite holds nothing: twice');
  // An account outside the group whose participant id is bo's place holds nothing.
  await seed('users/out', { name: 'Out', participantId: M.bo });
  assert.ok((await call('createInvite', 'me', link(M.bo))).result.token, 'an outsider\'s participant id is not a member\'s');
  // D-230 decision 64: a removed person's place is never invited again (it would change hands).
  assert.deepEqual(await call('createInvite', 'me', link(M.sam)), HELD_ERR, 'sam holds it while a member');
  assert.deepEqual((await call('removeMember', 'ad', remove('sam'))).result, { removed: true });
  assert.deepEqual(await call('createInvite', 'me', link(M.sam)), { error: 'FAILED_PRECONDITION' }, 'sam was removed');
});

test('D-218: a contested place is held: ALREADY_EXISTS, nothing written; D-227: a participant id two members share holds nothing', async () => {
  await seed('users/out', { name: 'Out', findable: true, participantId: '8'.repeat(32) });
  const refused = async (what) => {
    const invitesBefore = await snapshotOf('invites');
    const limitsBefore = await read('limits/me');
    assert.deepEqual(await call('createInvite', 'me', link(M.bo)), HELD_ERR, `${what}: createInvite`);
    assert.deepEqual(await call('inviteUser', 'me', { ...link(M.bo), targetUid: 'out' }), HELD_ERR, `${what}: inviteUser`);
    assert.deepEqual(await snapshotOf('invites'), invitesBefore, `${what}: no link invite was written`);
    assert.equal((await list('users/out/invites')).length, 0, `${what}: inviteUser delivered nothing`);
    assert.deepEqual(await read('limits/me'), limitsBefore, `${what}: no daily quota was spent`);
  };
  // Contested: two current members each hold an accepted invite for bo's place (a state acceptInvite refuses to make).
  await joined('ad', M.bo);
  await joined('sam', M.bo);
  await refused('contested');
  // D-227: two members whose accounts carry one participant id. A participant
  // id decides no place any more (only the creator's first create and accepted
  // invites do), so the id holds nothing and an invite for it is made.
  await seed('users/ad', { participantId: '6'.repeat(32), name: 'Asha' });
  await seed('users/sam', { participantId: '6'.repeat(32), name: 'Sam' });
  const shared = '6'.repeat(32);
  assert.ok((await call('createInvite', 'me', link(shared))).result.token, 'two members, one participant id: no place is held by it');
});

/** An unused invite to place `member`, made by a member, and its token. */
async function invite(member, token) {
  await seed(`invites/${createHash('sha256').update(token).digest('hex')}`, { groupId: G, memberId: member, memberName: 'Bo',
    groupName: 'Goa trip', inviterName: 'Mo', createdBy: 'me', createdAt: new Date(), expiresAt: new Date(Date.now() + 3600000), usedBy: null });
  return token;
}

test('D-210 fix 4: a place whose account was removed stays nobody\'s when that account joins another place', async () => {
  assert.ok((await call('removeMember', 'ad', remove('sam', { records: [removalAt(M.sam, 1)] }))).result);
  assert.ok((await call('acceptInvite', 'sam', { token: await invite(M.bo, 'R'.repeat(43)) })).result, 'sam joins place bo');
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam']);
  // The old invite for sam's first place (usedBy: sam) must not make sam that place again.
  assert.deepEqual(await call('requestRemoval', 'me', ask({ you: null })), { error: 'FAILED_PRECONDITION' }, 'removed already');
  assert.deepEqual(await call('removeMember', 'ow', remove('sam', { you: null, records: [removalAt(M.sam, 2)] })),
    { error: 'FAILED_PRECONDITION' }, 'removed already');
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam'], 'sam is untouched');
  assert.equal(await read(`groups/${G}/records/${removalAt(M.sam, 2).id}`), null);
  // The place sam holds now is still theirs.
  assert.deepEqual((await call('removeMember', 'ow', remove('bo', { you: null, records: [removalAt(M.bo, 3)] }))).result, { removed: true });
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me']);
});

test('D-210 fix 4: removeMember ends the removed account\'s used invites for that place, and only those', async () => {
  await seed('invites/h-other', { groupId: G, memberId: M.me, usedBy: 'sam' });
  assert.ok((await call('removeMember', 'ad', remove('sam', { records: [removalAt(M.sam, 1)] }))).result);
  const ended = await read(`invites/joined-sam-${M.sam.slice(0, 4)}`);
  assert.ok(ended.endedAt.timestampValue, 'the used invite for the place ends');
  assert.equal(ended.usedBy.stringValue, 'sam');
  assert.equal((await read(`invites/joined-me-${M.me.slice(0, 4)}`)).endedAt, undefined, 'another account\'s invite');
  assert.equal((await read('invites/h-other')).endedAt, undefined, 'another place\'s invite');
});

test('D-227 (was D-210 fix 4\'s collision test): a joiner whose participant id copies a place\'s id holds only the place it joined', async () => {
  // D-227 removed acceptInvite's collision check: a participant id resolves no
  // place, so a copied one gives nothing. Before D-227 the server resolved an
  // unjoined place to whoever's participant id equalled it (the hole closed here).
  const K1 = '6a'.repeat(16);
  const K2 = '6b'.repeat(16);
  await op('ad', 11, { op: 'addMember', group: G, member: { id: K1, name: 'Kim' } });
  await op('ad', 12, { op: 'addMember', group: G, member: { id: K2, name: 'Kai' } });
  await seed('users/imp1', { participantId: P.ow, name: 'imp1' }); // the creator's place id
  await seed('users/imp2', { participantId: M.bo, name: 'imp2' }); // a place nobody joined
  assert.ok((await call('acceptInvite', 'imp1', { token: await invite(K1, 'A'.repeat(43)) })).result, 'the owner\'s place id');
  assert.ok((await call('acceptInvite', 'imp2', { token: await invite(K2, 'B'.repeat(43)) })).result, 'bo\'s place id');
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam', 'imp1', 'imp2']);
  // The role calls never treat the impostor as the unjoined place's person.
  assert.deepEqual(await call('setAdmin', 'ow', roles({ member: M.bo, you: M.bo, on: true })), { error: 'NOT_FOUND' },
    'setAdmin on the unjoined place, named by the impostor\'s copied id');
  assert.deepEqual(strings((await read(`groups/${G}`)).admins), ['ad'], 'nobody was made an admin');
  // Each holds only the place it joined.
  assert.deepEqual((await call('groupRoles', 'imp1', { groupId: G })).result.places, { owner: [M.ow], admins: [M.ad], you: [K1] });
  assert.deepEqual((await call('groupRoles', 'imp2', { groupId: G })).result.places, { owner: [M.ow], admins: [M.ad], you: [K2] });
  const before = await recordsOf();
  assert.deepEqual((await call('shareProfile', 'imp1', { records: [profileOf(M.ow, 'imp1')] })).result, { shared: [], skipped: [G] },
    'no profile on the creator\'s place');
  assert.deepEqual((await call('shareProfile', 'imp2', { records: [profileOf(M.bo, 'imp2')] })).result, { shared: [], skipped: [G] },
    'no profile on the unjoined place (the hole)');
  assert.deepEqual(await recordsOf(), before, 'nothing written');
  assert.ok((await call('createInvite', 'me', link(M.bo))).result.token, 'the unjoined place is still free to invite');
  // Nobody was made immune or contested: the owner still removes sam, and the owner's place stays the owner's.
  assert.deepEqual((await call('removeMember', 'ad', remove('sam'))).result, { removed: true });
  assert.deepEqual(await call('removeMember', 'ad', remove('ow', { you: null })), { error: 'PERMISSION_DENIED' });
});

test('D-210 fix 4: an ordinary joiner joins, and the same account accepting again is still allowed', async () => {
  assert.ok((await call('acceptInvite', 'eve', { token: await invite(M.bo, 'C'.repeat(43)) })).result);
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam', 'eve']);
  assert.ok((await call('acceptInvite', 'eve', { token: await invite(M.bo, 'D'.repeat(43)) })).result, 'the same place again, same account');
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam', 'eve']);
  assert.ok((await call('acceptInvite', 'sam', { token: await invite(M.sam, 'E'.repeat(43)) })).result, 'a member accepting their own place');
});

test('a member with another member\'s participant id does not stop the owner removing them', async () => {
  const imp = '7'.repeat(32);
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam', 'imp'], createdBy: 'ow', admins: ['ad'] });
  await seed('users/imp', { participantId: P.sam, name: 'imp' });
  await op('ow', 11, { op: 'addMember', group: G, member: { id: imp, name: 'Imp' } });
  await joined('imp', imp);
  assert.deepEqual((await call('removeMember', 'ow', remove('sam', { member: imp, records: [removalAt(imp, 1)] }))).result,
    { removed: true }, 'the impostor, named by their own (colliding) id');
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam']);
  assert.deepEqual((await call('removeMember', 'ow', remove('sam', { records: [removalAt(M.sam, 2)] }))).result, { removed: true });
});

test('declineRemoval decides on whose place it is now: one made admin since the request is the owner\'s', async () => {
  assert.ok((await call('requestRemoval', 'me', ask({ member: M.bo, you: null, memberName: 'Bo' }))).result);
  await seed('users/bo', { participantId: '7'.repeat(32), name: 'bo' });
  const { result: { token } } = await call('createInvite', 'ow',
    { groupId: G, memberId: M.bo, memberName: 'Bo', groupName: 'Goa trip', inviterName: 'Ola' });
  assert.ok((await call('acceptInvite', 'bo', { token })).result);
  assert.ok((await call('setAdmin', 'ow', roles({ you: '7'.repeat(32), on: true }))).result);
  assert.deepEqual(await call('declineRemoval', 'ad', decline({ member: M.bo })), { error: 'PERMISSION_DENIED' });
  assert.ok(await read(`groups/${G}/requests/${M.bo}`), 'still open');
  assert.deepEqual((await call('declineRemoval', 'ow', decline({ member: M.bo }))).result, { declined: true });
  assert.equal(await read(`groups/${G}/requests/${M.bo}`), null);
});

test('requestRemoval: a place that stands removed cannot be asked about', async () => {
  assert.ok((await call('removeMember', 'ad', remove('bo', { you: null, records: [removalAt(M.bo, 1)] }))).result);
  assert.deepEqual(await call('requestRemoval', 'me', ask({ member: M.bo, you: null, memberName: 'Bo' })), { error: 'FAILED_PRECONDITION' });
  assert.ok((await call('removeMember', 'ad', remove('sam', { records: [removalAt(M.sam, 2)] }))).result);
  assert.deepEqual(await call('requestRemoval', 'me', ask({ you: null })), { error: 'FAILED_PRECONDITION' }, 'joined, then removed');
  assert.deepEqual(await list(`groups/${G}/requests`), []);
});

test('participant ids are not unique: an outsider with a member\'s id changes nothing', async () => {
  await seed('users/x', { participantId: P.sam, name: 'x' });
  await seed('users/y', { participantId: P.ow, name: 'y' });
  await seed('users/z', { participantId: P.ad, name: 'z' });
  assert.deepEqual((await call('setAdmin', 'ow', roles({ you: P.sam, on: true }))).result, { admins: [P.ad, P.sam] });
  assert.deepEqual(await call('removeMember', 'ad', remove('ow', { you: null })), { error: 'PERMISSION_DENIED' }, 'the owner is still the owner');
  // A request about an admin is still the owner's alone to decline.
  assert.ok((await call('requestRemoval', 'me', ask({ member: M.ad, you: P.ad, memberName: 'Asha' }))).result);
  assert.deepEqual(await call('declineRemoval', 'sam', decline({ member: M.ad })), { error: 'PERMISSION_DENIED' });
  assert.deepEqual((await call('removeMember', 'ow', remove('sam'))).result, { removed: true });
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me']);
  assert.deepEqual(roleIds(await call('groupRoles', 'me', { groupId: G })), { owner: P.ow, admins: [P.ad] });
});

test('requestRemoval: only a place in the group can be asked about', async () => {
  const nia = '8'.repeat(32);
  assert.deepEqual(await call('requestRemoval', 'me', ask({ member: nia, you: null, memberName: 'Nia' })), { error: 'NOT_FOUND' });
  assert.deepEqual(await call('requestRemoval', 'me', ask({ member: P.eve, you: null, memberName: 'Eve' })), { error: 'NOT_FOUND' }, 'an outsider\'s own id');
  await op('ad', 12, { op: 'addMember', group: 'e'.repeat(32), member: { id: nia, name: 'Nia' } });
  assert.deepEqual(await call('requestRemoval', 'me', ask({ member: nia, you: null, memberName: 'Nia' })), { error: 'NOT_FOUND' }, 'another group\'s op');
  assert.deepEqual(await list(`groups/${G}/requests`), []);
  await op('ad', 11, { op: 'addMember', group: G, member: { id: nia, name: 'Nia' } });
  assert.deepEqual((await call('requestRemoval', 'me', ask({ member: nia, you: null, memberName: 'Nia' }))).result, { requested: true });
});

test('removeMember: someone already removed is not removed again', async () => {
  assert.ok((await call('removeMember', 'ad', remove('bo', { you: null, records: [removalAt(M.bo, 1)] }))).result);
  assert.deepEqual(await call('removeMember', 'ad', remove('bo', { you: null, records: [removalAt(M.bo, 2)] })), { error: 'FAILED_PRECONDITION' },
    'never joined');
  assert.ok((await call('removeMember', 'ad', remove('sam', { records: [removalAt(M.sam, 3)] }))).result);
  assert.deepEqual(await call('removeMember', 'ad', remove('sam', { you: null, records: [removalAt(M.sam, 4)] })), { error: 'FAILED_PRECONDITION' },
    'joined, then passed off as never joined');
  assert.equal(await read(`groups/${G}/records/${removalAt(M.bo, 2).id}`), null);
  assert.equal(await read(`groups/${G}/records/${removalAt(M.sam, 4).id}`), null);
});

test('D-210 fix 2: a removed place can be removed again after a phone adds it back', async () => {
  const pause = () => new Promise((r) => setTimeout(r, 50));
  assert.ok((await call('removeMember', 'ad', remove('bo', { you: null, records: [removalAt(M.bo, 1)] }))).result);
  assert.deepEqual(await call('removeMember', 'ad', remove('bo', { you: null, records: [removalAt(M.bo, 2)] })), { error: 'FAILED_PRECONDITION' },
    'no re-add yet');
  await pause();
  // A phone adds the place back (a later add of the same id brings it back).
  await op('ad', 20, { op: 'addMember', group: G, member: { id: M.bo, name: 'Bo' } });
  await pause();
  assert.ok((await call('removeMember', 'ad', remove('bo', { you: null, records: [removalAt(M.bo, 3)] }))).result,
    'added back, so it can be removed');
  assert.deepEqual(await call('removeMember', 'ad', remove('bo', { you: null, records: [removalAt(M.bo, 4)] })), { error: 'FAILED_PRECONDITION' },
    'removed again, not added back again');
  assert.equal(await read(`groups/${G}/records/${removalAt(M.bo, 2).id}`), null);
  assert.equal(await read(`groups/${G}/records/${removalAt(M.bo, 4).id}`), null);
  assert.ok(await read(`groups/${G}/records/${removalAt(M.bo, 3).id}`));
});

test('D-210 fix 2: an add the phone dates in the future (its own clock) does not unlock a removal', async () => {
  assert.ok((await call('removeMember', 'ad', remove('bo', { you: null, records: [removalAt(M.bo, 1)] }))).result);
  // The HLC is the phone's to choose; only the server's createdAt counts. Here the
  // add's HLC is far ahead but it was written BEFORE the removal.
  await seed(`groups/${G}/records/${9000000000000}-0-${D}`, {
    type: '3', hlc: { millis: 9000000000000, counter: 0, device: D }, authorUid: 'ad', deviceId: D, createdAt: new Date(Date.now() - 60000),
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes({ op: 'addMember', group: G, member: { id: M.bo, name: 'Bo' } }) },
  });
  assert.deepEqual(await call('removeMember', 'ad', remove('bo', { you: null, records: [removalAt(M.bo, 2)] })), { error: 'FAILED_PRECONDITION' });
});

// ---- D-210 final review (wave A) ----

test('A2: setAdmin and makeOwner resolve the place on the server; a forged claim never redirects them', async () => {
  await claim('me', -1, M.ad); // me's phone claims Asha's place
  await claim('me', -2, M.sam); // and Sam's
  // A phone fooled by the claims names me's account for those places.
  assert.deepEqual(await call('makeOwner', 'ow', roles({ member: M.ad, you: P.me })), { error: 'NOT_FOUND' });
  assert.deepEqual(await call('setAdmin', 'ow', roles({ member: M.ad, you: P.me, on: false })), { error: 'NOT_FOUND' });
  assert.deepEqual(await call('setAdmin', 'ad', roles({ member: M.sam, you: P.me, on: true })), { error: 'NOT_FOUND' });
  let g = await read(`groups/${G}`);
  assert.equal(g.owner, undefined, 'the owner is unchanged (still its creator)');
  assert.deepEqual(strings(g.admins), ['ad'], 'me was made nothing');
  assert.deepEqual(await kinds('me'), [], 'and told nothing');
  // A place nobody holds, a place not in the group, and no place at all.
  assert.deepEqual(await call('setAdmin', 'ow', roles({ member: M.bo, you: P.me, on: true })), { error: 'NOT_FOUND' });
  assert.deepEqual(await call('setAdmin', 'ow', roles({ member: '8'.repeat(32), you: P.me, on: true })), { error: 'NOT_FOUND' });
  assert.deepEqual(await call('makeOwner', 'ow', { groupId: G, groupName: 'Goa trip', you: P.ad }), { error: 'INVALID_ARGUMENT' });
  // The account that holds the place is the one changed.
  assert.deepEqual((await call('setAdmin', 'ad', roles({ member: M.sam, you: P.sam, on: true }))).result, { admins: [P.ad, P.sam] });
  assert.deepEqual((await call('makeOwner', 'ow', roles({ member: M.ad, you: P.ad }))).result, { owner: P.ad });
  g = await read(`groups/${G}`);
  assert.equal(g.owner.stringValue, 'ad');
  assert.deepEqual(strings(g.admins), ['sam', 'ow']);
});

/** A group whose only member is ow (the phone kept removals local until it was shared). D-235 round 3: and that
 *  never had anyone else, so the fixture's accepted invites go too (adoptRemovals refuses a group someone joined). */
async function alone() {
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow'], createdBy: 'ow', createdAt: new Date() });
  for (const uid of ['ad', 'me', 'sam']) {
    const r = await fetch(`${FS}/v1/${DOCS}/invites/joined-${uid}-${M[uid].slice(0, 4)}`, { method: 'DELETE', headers: { authorization: 'Bearer owner' } });
    assert.equal(r.status, 200);
  }
}

test('A4: adoptRemovals writes a sole member\'s earlier removals with the removal mark', async () => {
  await alone();
  const records = [removalOf(M.sam), movedFrom(M.sam), removalAt(M.bo, 5)];
  assert.deepEqual((await call('adoptRemovals', 'ow', { groupId: G, records })).result, { adopted: 3 });
  const at = new Set();
  for (const r of records) {
    const doc = await read(`groups/${G}/records/${r.id}`);
    assert.equal(doc.removal.booleanValue, true, r.id);
    assert.equal(doc.authorUid.stringValue, 'ow');
    assert.equal(doc.type.stringValue, r.type, 'a string');
    assert.equal(doc.deviceId.stringValue, D);
    assert.equal(doc.hlc.mapValue.fields.counter.integerValue, '0');
    assert.equal(doc.payload.mapValue.fields.blob.bytesValue, r.payload.blob, 'Bytes');
    assert.equal(doc.payload.mapValue.fields.nonce.bytesValue, r.payload.nonce);
    at.add(doc.createdAt.timestampValue);
  }
  assert.equal(at.size, 1, 'one server time for the lot');
  // A retry of the same records: already there, nothing changes.
  assert.deepEqual(await call('adoptRemovals', 'ow', { groupId: G, records }), { error: 'ALREADY_EXISTS' });
  assert.deepEqual(await call('adoptRemovals', 'ow', { groupId: G, records: [removalAt(M.me, 6), removalOf(M.sam)] }), { error: 'ALREADY_EXISTS' });
  assert.equal(await read(`groups/${G}/records/${removalAt(M.me, 6).id}`), null, 'all or nothing');
  // The adopted removal counts as one: removeMember sees Sam's place removed.
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad'], createdBy: 'ow' });
  assert.deepEqual(await call('removeMember', 'ow', remove('bo', { you: null, records: [removalAt(M.bo, 7)] })), { error: 'FAILED_PRECONDITION' });
});

test('A4: adoptRemovals refuses a group with others in it, a stranger, and records that are not removals of this group', async () => {
  const records = [removalOf(M.sam), movedFrom(M.sam)];
  assert.deepEqual(await call('adoptRemovals', 'ow', { groupId: G, records }), { error: 'FAILED_PRECONDITION' }, 'two members or more');
  assert.equal(await read(`groups/${G}/records/${records[0].id}`), null);
  await alone();
  assert.deepEqual(await call('adoptRemovals', 'me', { groupId: G, records }), { error: 'PERMISSION_DENIED' }, 'a stranger');
  assert.deepEqual(await call('adoptRemovals', null, { groupId: G, records }), { error: 'UNAUTHENTICATED' });
  const gone = 'e'.repeat(32);
  assert.deepEqual(await call('adoptRemovals', 'ow', { groupId: gone, records: [rec('3', { op: 'removeMember', group: gone, member: M.sam })] }),
    { error: 'NOT_FOUND' }, 'no such group');
  assert.deepEqual(await call('adoptRemovals', 'ow', { groupId: 'x', records }), { error: 'INVALID_ARGUMENT' });
  const other = 'e'.repeat(32);
  for (const bad of [
    [], 'x', null, undefined,
    [rec('1', { group: G })],
    [rec('3', { op: 'addMember', group: G, member: { id: M.bo, name: 'Bo' } })],
    [rec('3', { op: 'claim', group: G, member: M.sam })],
    [rec('3', { op: 'removeMember', group: other, member: M.sam })],
    [rec('3', { op: 'removeMember', group: G, member: 'sam' })],
    [rec('6', { group: other, from: M.sam, currency: 'INR', shares: [] })],
    [rec('6', { group: G, from: 'sam', currency: 'INR', shares: [] })],
    [removalOf(M.sam), removalOf(M.sam)],
    [{ ...removalOf(M.sam), id: 'x' }],
    [{ ...removalOf(M.sam), payload: { ...removalOf(M.sam).payload, blob: '%%%' } }],
    Array.from({ length: 51 }, (_, n) => removalAt(M.sam, 100 + n)),
  ]) {
    assert.deepEqual(await call('adoptRemovals', 'ow', { groupId: G, records: bad }), { error: 'INVALID_ARGUMENT' }, JSON.stringify(bad)?.slice(0, 120));
  }
  assert.equal((await list(`groups/${G}/records`)).length, RECORDS, 'nothing written');
  assert.deepEqual((await call('adoptRemovals', 'ow', { groupId: G, records: Array.from({ length: 50 }, (_, n) => removalAt(M.sam, 100 + n)) })).result,
    { adopted: 50 }, 'fifty is the bound');
});

test('A5: removeMember removes only a place the group\'s log names', async () => {
  const nia = '8'.repeat(32);
  assert.deepEqual(await call('removeMember', 'ad', remove('bo', { member: nia, you: null, records: [removalAt(nia, 1)] })), { error: 'NOT_FOUND' });
  await op('ad', 12, { op: 'addMember', group: 'e'.repeat(32), member: { id: nia, name: 'Nia' } });
  assert.deepEqual(await call('removeMember', 'ad', remove('bo', { member: nia, you: null, records: [removalAt(nia, 1)] })), { error: 'NOT_FOUND' },
    'another group\'s op');
  assert.equal(await read(`groups/${G}/records/${removalAt(nia, 1).id}`), null, 'nothing written');
  await op('ad', 13, { op: 'addMember', group: G, member: { id: nia, name: 'Nia' } });
  assert.deepEqual((await call('removeMember', 'ad', remove('bo', { member: nia, you: null, records: [removalAt(nia, 1)] }))).result, { removed: true });
});

test('A5: makeOwner closes an open request about the new owner, and its notices', async () => {
  assert.ok((await call('requestRemoval', 'me', ask())).result);
  assert.ok((await call('requestRemoval', 'me', ask({ member: M.bo, you: null, memberName: 'Bo' }))).result);
  assert.deepEqual((await call('makeOwner', 'ow', roles({ you: P.sam }))).result, { owner: P.sam });
  assert.equal(await read(`groups/${G}/requests/${M.sam}`), null, 'nobody asks to remove the owner');
  assert.ok(await read(`groups/${G}/requests/${M.bo}`), 'another request stays');
  for (const uid of ['ow', 'ad', 'me', 'sam']) {
    const ids = (await notices(uid)).filter((n) => n.kind.stringValue === 'request').map((n) => n.id);
    assert.ok(!ids.includes(`request-${G}-${M.sam}`), uid);
  }
  assert.ok((await notices('ow')).some((n) => n.id === `request-${G}-${M.bo}`), 'the other request\'s notice stays');
});

test('M2: a side effect that fails after the hand-over committed never fails makeOwner', async () => {
  // A request written without its person's name makes showRequests throw
  // (Firestore refuses an undefined field), after the transaction committed.
  await seed(`groups/${G}/requests/${M.bo}`, { member: M.bo, askerUid: 'sam', askerName: 'Sam', you: null });
  const first = await call('makeOwner', 'ow', roles({ you: P.ad }));
  assert.deepEqual(first.result, { owner: P.ad }, 'the callable answers success once the ownership changed');
  const group = await read(`groups/${G}`);
  assert.equal(group.owner.stringValue, 'ad', 'ownership did change');
  assert.ok((await notices('ad')).some((n) => n.kind.stringValue === 'role' && n.role.stringValue === 'owner'),
    'the step before the failing one still ran');
  // The phone's retry after a lost answer is refused (no longer the owner): why a throw here was a defect.
  assert.deepEqual(await call('makeOwner', 'ow', roles({ you: P.ad })), { error: 'PERMISSION_DENIED' });
});

test('a side effect that fails after the removal committed never fails removeMember', async () => {
  // A request whose asker is a number, not a participant id: the removal
  // deletes the request in its transaction, then clearing the notices throws
  // (users/{uid} needs a string), after the removal committed.
  await seed(`groups/${G}/requests/${M.sam}`, { member: M.sam, askerUid: 123, askerName: 'Sam', memberName: 'Sam' });
  const first = await call('removeMember', 'ad', remove('sam'));
  assert.deepEqual(first, { result: { removed: true } }, 'the callable answers success once the removal committed');
  const group = await read(`groups/${G}`);
  assert.deepEqual(strings(group.memberUids), ['ow', 'ad', 'me'], 'sam is no longer a member');
  assert.ok(group.former?.mapValue?.fields?.sam, 'and is kept as former');
  assert.ok((await kinds('sam')).includes('removed'), 'the step after the failing one still ran: the removed person is told');
});

test('a side effect that fails after the change committed never fails setAdmin', async () => {
  // A request written without its person's name makes showRequests throw
  // (Firestore refuses an undefined field), after the transaction committed.
  await seed(`groups/${G}/requests/${M.bo}`, { member: M.bo, askerUid: 'sam', askerName: 'Sam', you: null });
  const first = await call('setAdmin', 'ow', roles({ you: P.me, on: true }));
  assert.deepEqual(first.result, { admins: [P.ad, P.me] }, 'the callable answers success once the admins changed');
  assert.deepEqual(strings((await read(`groups/${G}`)).admins), ['ad', 'me'], 'me is an admin');
  assert.deepEqual(await kinds('me'), ['role'], 'the step before the failing one still ran: the new admin is told');
  // The phone's retry after a lost answer is refused (already an admin): why a throw here was a defect.
  assert.deepEqual(await call('setAdmin', 'ow', roles({ you: P.me, on: true })), { error: 'FAILED_PRECONDITION' });
});

test('a side effect that fails after the request was deleted never fails declineRemoval', async () => {
  // A request whose asker is a number: the decline deletes the request in its
  // transaction, then clearing the notices throws (users/{uid} needs a
  // string), after the decline committed.
  await seed(`groups/${G}/requests/${M.sam}`, { member: M.sam, you: P.sam, askerUid: 123, askerName: 'Mo', memberName: 'Sam' });
  const first = await call('declineRemoval', 'ad', decline());
  assert.deepEqual(first, { result: { declined: true } }, 'the callable answers success once the request is gone');
  assert.equal(await read(`groups/${G}/requests/${M.sam}`), null, 'the request is gone');
  assert.deepEqual(await kinds('sam'), ['declined'], 'the step after the failing one still ran: the person is told');
  // The phone's retry after a lost answer finds no request: why a throw here was a defect.
  assert.deepEqual(await call('declineRemoval', 'ad', decline()), { error: 'NOT_FOUND' });
});

test('a side effect that fails after the group is gone never fails deleteGroup', async () => {
  // An invitation delivered to a malformed account (users/a/b is no
  // document) makes ending the invitations throw, after the group was deleted.
  await seed('invites/odd', {
    groupId: G, memberId: M.bo, memberName: 'Bo', groupName: 'Goa trip', inviterName: 'Ola', createdBy: 'ow',
    createdAt: new Date(), expiresAt: new Date(Date.now() + 3600000), deliveredTo: 'a/b',
  });
  const first = await call('deleteGroup', 'ow', { groupId: G, groupName: 'Goa trip' });
  assert.deepEqual(first, { result: { deleted: true } }, 'the callable answers success once the group is gone');
  assert.equal(await read(`groups/${G}`), null, 'the group is gone');
  for (const uid of ['ad', 'me', 'sam']) {
    assert.deepEqual(await kinds(uid), ['deleted'], `the step after the failing one still ran: ${uid} is told`);
  }
  // The phone's retry after a lost answer finds no group: why a throw here was a defect.
  assert.deepEqual(await call('deleteGroup', 'ow', { groupId: G, groupName: 'Goa trip' }), { error: 'NOT_FOUND' });
});

test('A5: notices name the actor from the server, never from the phone', async () => {
  const lie = 'The Owner';
  assert.ok((await call('setAdmin', 'ad', roles({ you: P.me, on: true, byName: lie }))).result);
  assert.equal((await notices('me')).find((n) => n.kind.stringValue === 'role').byName.stringValue, 'Asha');
  assert.ok((await call('requestRemoval', 'sam', ask({ member: M.bo, you: null, memberName: 'Bo', askerName: lie }))).result);
  assert.equal((await read(`groups/${G}/requests/${M.bo}`)).askerName.stringValue, 'Sam');
  assert.equal((await notices('ow')).find((n) => n.id === `request-${G}-${M.bo}`).byName.stringValue, 'Sam');
  assert.ok((await call('declineRemoval', 'ow', decline({ member: M.bo, byName: lie }))).result);
  assert.equal((await notices('sam')).find((n) => n.kind.stringValue === 'declined').byName.stringValue, 'Ola');
  assert.ok((await call('makeOwner', 'ow', roles({ you: P.ad, byName: lie }))).result);
  assert.equal((await notices('ad')).find((n) => n.kind.stringValue === 'role').byName.stringValue, 'Ola');
  // An account with no name is "Someone"; the phone's name is not needed at all.
  await seed('users/ad', { participantId: P.ad });
  assert.ok((await call('removeMember', 'ad', { groupId: G, member: M.sam, you: P.sam, memberName: 'Sam', groupName: 'Goa trip',
    records: [removalAt(M.sam, 3)] })).result);
  const removed = (await notices('sam')).find((n) => n.kind.stringValue === 'removed');
  assert.equal(removed.byName.stringValue, 'Someone');
  assert.equal(removed.memberName.stringValue, 'Sam', 'the person\'s name in the group stays the phone\'s');
  assert.ok((await call('deleteGroup', 'ad', { groupId: G, groupName: 'Goa trip', byName: lie })).result);
  assert.equal((await notices('me')).find((n) => n.kind.stringValue === 'deleted').byName.stringValue, 'Someone');
});

test('A5: deleteGroup also ends the group\'s unused invites and every in-app copy of them', async () => {
  const other = 'e'.repeat(32);
  await seed('invites/h-unused', { groupId: G, memberId: M.bo, usedBy: null, deliveredTo: 'eve' });
  await seed('invites/h-link', { groupId: G, memberId: M.bo, usedBy: null });
  await seed('invites/h-other', { groupId: other, memberId: M.bo, usedBy: null, deliveredTo: 'eve' });
  await seed('users/eve/invites/c1', { groupId: G, memberId: M.bo, token: 't1' });
  await seed('users/eve/invites/c2', { groupId: other, memberId: M.bo, token: 't2' });
  await seed('users/me/invites/c3', { groupId: G, memberId: M.bo, token: 't3' });
  assert.ok((await call('deleteGroup', 'ow', { groupId: G, groupName: 'Goa trip' })).result);
  assert.equal(await read('invites/h-unused'), null);
  assert.equal(await read('invites/h-link'), null);
  assert.ok(await read('invites/h-other'), 'another group\'s invite');
  assert.equal(await read('users/eve/invites/c1'), null, 'a copy delivered to someone outside the group');
  assert.equal(await read('users/me/invites/c3'), null, 'a member\'s copy');
  assert.ok(await read('users/eve/invites/c2'), 'another group\'s copy');
  assert.ok(await read(`invites/joined-sam-${M.sam.slice(0, 4)}`), 'a used invite is history');
  assert.deepEqual(await kinds('me'), ['deleted']);
});

test('A5: two members joined to one place by their invites is contested: nobody is removed or asked about', async () => {
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam', 'eve'], createdBy: 'ow', admins: ['ad'] });
  await joined('eve', M.sam); // a second account's used invite on Sam's place (seeded: acceptInvite refuses it)
  assert.deepEqual(await call('removeMember', 'ow', remove('sam', { you: null })), { error: 'FAILED_PRECONDITION' });
  assert.deepEqual(await call('removeMember', 'ow', remove('sam')), { error: 'FAILED_PRECONDITION' });
  assert.deepEqual(await call('requestRemoval', 'me', ask({ you: null })), { error: 'FAILED_PRECONDITION' });
  assert.deepEqual(await call('setAdmin', 'ow', roles({ you: P.sam, on: true })), { error: 'FAILED_PRECONDITION' });
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam', 'eve']);
  assert.equal((await list(`groups/${G}/records`)).length, RECORDS, 'nothing written');
});

// ---- shareProfile ----

/** A memberProfile record as the phones build it (core's GroupOp::MemberProfile). */
const profileOf = (member, name, extra = {}, n = 1, group = G) =>
  rec('3', { op: 'memberProfile', group, member, name, ...extra }, n);
// A JPEG start, then its frame header (SOF0: 8 bits, 120 x 120, three components), then filler.
const THUMB = `ffd8ffc0001108007800780301110002110103110100${'ab'.repeat(40)}`;
const recordsOf = async (group = G) => (await list(`groups/${group}/records`)).map(idOf);

test('shareProfile: a member\'s own name and preview reach the group, marked', async () => {
  const r = profileOf(M.me, 'Mo', { thumb: THUMB });
  assert.deepEqual((await call('shareProfile', 'me', { records: [r] })).result, { shared: [G], skipped: [] });
  const doc = await read(`groups/${G}/records/${r.id}`);
  assert.equal(doc.profile.booleanValue, true);
  assert.equal(doc.authorUid.stringValue, 'me');
  assert.equal(doc.type.stringValue, '3');
  assert.equal(doc.payload.mapValue.fields.blob.bytesValue, r.payload.blob, 'the blob\'s bytes, as the phone built them');
  assert.ok(doc.createdAt.timestampValue, 'the server\'s time');
  assert.equal(doc.removal, undefined, 'never a removal');
});

test('shareProfile refuses a name that is not the account\'s, and an account with no name', async () => {
  const before = await recordsOf();
  assert.deepEqual(await call('shareProfile', 'me', { records: [profileOf(M.me, 'Mona')] }), { error: 'FAILED_PRECONDITION' });
  await seed('users/eve', { participantId: P.eve });
  assert.deepEqual(await call('shareProfile', 'eve', { records: [profileOf(M.me, 'Mo')] }), { error: 'FAILED_PRECONDITION' });
  assert.deepEqual(await recordsOf(), before, 'nothing written');
});

test('shareProfile never writes on someone else\'s place', async () => {
  const before = await recordsOf();
  assert.deepEqual((await call('shareProfile', 'me', { records: [profileOf(M.sam, 'Mo')] })).result, { shared: [], skipped: [G] });
  assert.deepEqual((await call('shareProfile', 'me', { records: [profileOf(M.bo, 'Mo', {}, 2)] })).result, { shared: [], skipped: [G] }, 'a place nobody holds');
  assert.deepEqual(await recordsOf(), before, 'nothing written');
});

test('shareProfile: a copied participant id never writes on another\'s place', async () => {
  // Participant ids are not unique and a phone writes its own (firestore.rules),
  // so me can copy sam's: holderIn resolves M.sam to sam through the invite and
  // placeIn's `you` check passes for me. Only the target check stops the write.
  await seed('users/me', { participantId: P.sam, name: NAME.me });
  const before = await recordsOf();
  assert.deepEqual((await call('shareProfile', 'me', { records: [profileOf(M.sam, 'Mo')] })).result, { shared: [], skipped: [G] });
  assert.deepEqual(await recordsOf(), before, 'nothing written');
});

test('shareProfile skips a place the group\'s own log never made', async () => {
  const ghost = '8'.repeat(32);
  await joined('me', ghost); // an accepted invite for a member id no create or addMember op names
  const before = await recordsOf();
  assert.deepEqual((await call('shareProfile', 'me', { records: [profileOf(ghost, 'Mo')] })).result, { shared: [], skipped: [G] });
  assert.deepEqual(await recordsOf(), before, 'nothing written');
});

test('shareProfile skips a contested place and writes an emoji preview', async () => {
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam', 'eve'], createdBy: 'ow', admins: ['ad'] });
  await joined('eve', M.sam); // a second account's used invite on Sam's place
  const before = await recordsOf();
  assert.deepEqual((await call('shareProfile', 'sam', { records: [profileOf(M.sam, 'Sam')] })).result, { shared: [], skipped: [G] });
  assert.deepEqual(await recordsOf(), before, 'nothing written');
  const e = profileOf(M.me, 'Mo', { emoji: '\u{1F33F}' }, 3);
  assert.deepEqual((await call('shareProfile', 'me', { records: [e] })).result, { shared: [G], skipped: [] });
  const blob = JSON.parse(Buffer.from((await read(`groups/${G}/records/${e.id}`)).payload.mapValue.fields.blob.bytesValue, 'base64').toString('utf8'));
  assert.equal(blob.emoji, '\u{1F33F}');
  assert.equal(blob.thumb, undefined);
  assert.equal((await read(`groups/${G}/records/${e.id}`)).profile.booleanValue, true);
});

test('shareProfile skips a group the caller is not in and writes the others', async () => {
  const G2 = 'a'.repeat(32);
  await seed(`groups/${G2}`, { name: 'Pune', memberUids: ['ow', 'sam'], createdBy: 'ow', createdAt: new Date() });
  await seed(`groups/${G2}/records/${1000}-0-${D}`, {
    type: '3', hlc: { millis: 1000, counter: 0, device: D }, authorUid: 'ow', deviceId: D, createdAt: new Date(),
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes({ op: 'create', group: G2, name: 'Pune', settlementCurrency: 'INR',
      members: [{ id: M.ow, name: 'Ola' }, { id: M.sam, name: 'Sam' }] }) },
  });
  const before = await recordsOf(G2);
  const ok = profileOf(M.me, 'Mo', {}, 1);
  const out = profileOf(M.me, 'Mo', {}, 2, G2);
  assert.deepEqual((await call('shareProfile', 'me', { records: [ok, out] })).result, { shared: [G], skipped: [G2] });
  assert.ok((await recordsOf()).includes(ok.id), 'the group it is in is written');
  assert.deepEqual(await recordsOf(G2), before, 'the other has no new record');
});

test('shareProfile refuses malformed records and is idempotent', async () => {
  const many = Array.from({ length: 51 }, (_, i) => profileOf(M.me, 'Mo', {}, i + 1, (i + 1).toString(16).padStart(32, '0')));
  assert.deepEqual(await call('shareProfile', 'me', { records: many }), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('shareProfile', 'me', { records: [] }), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('shareProfile', 'me', {}), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('shareProfile', null, { records: [profileOf(M.me, 'Mo')] }), { error: 'UNAUTHENTICATED' });
  const r = profileOf(M.me, 'Mo');
  assert.deepEqual((await call('shareProfile', 'me', { records: [r] })).result, { shared: [G], skipped: [] });
  assert.deepEqual((await call('shareProfile', 'me', { records: [r] })).result, { shared: [G], skipped: [] }, 'a retry counts as shared');
  assert.equal((await recordsOf()).filter((id) => id === r.id).length, 1, 'one document');
});

// ---- D-211 final review (founder decision 5): one preview per account per group ----

/** The ids of the marked profile records `uid` wrote in the group. */
const profilesBy = async (uid, group = G) => (await list(`groups/${group}/records`))
  .filter((d) => d.fields.profile?.booleanValue === true && d.fields.authorUid?.stringValue === uid).map(idOf).sort();

test('shareProfile: a new preview deletes your earlier ones in the group, and nobody else\'s', async () => {
  const first = profileOf(M.me, 'Mo', { thumb: THUMB }, 1);
  const sams = profileOf(M.sam, 'Sam', { emoji: '\u{1F33F}' }, 2);
  assert.deepEqual((await call('shareProfile', 'me', { records: [first] })).result, { shared: [G], skipped: [] });
  assert.deepEqual((await call('shareProfile', 'sam', { records: [sams] })).result, { shared: [G], skipped: [] });
  const second = profileOf(M.me, 'Mo', { emoji: '\u{1F98A}' }, 3);
  assert.deepEqual((await call('shareProfile', 'me', { records: [second] })).result, { shared: [G], skipped: [] });
  assert.deepEqual(await profilesBy('me'), [second.id], 'exactly one: the one just written');
  assert.deepEqual(await profilesBy('sam'), [sams.id], 'another member\'s profile is untouched');
  assert.equal((await recordsOf()).length, RECORDS + 2, 'the group\'s own records stay');
  // A retry of the same record (a lost answer): shared, and nothing deleted.
  assert.deepEqual((await call('shareProfile', 'me', { records: [second] })).result, { shared: [G], skipped: [] });
  assert.deepEqual(await profilesBy('me'), [second.id], 'the retry deletes nothing');
  assert.deepEqual(await profilesBy('sam'), [sams.id]);
});

test('shareProfile keeps your latest preview: a late retry of an earlier one never replaces it', async () => {
  const first = profileOf(M.me, 'Mo', { thumb: THUMB }, 1);
  const second = profileOf(M.me, 'Mo', { emoji: '\u{1F98A}' }, 3);
  await call('shareProfile', 'me', { records: [first] });
  await call('shareProfile', 'me', { records: [second] });
  assert.deepEqual(await profilesBy('me'), [second.id]);
  // The first call's answer was lost and its retry arrives after the second:
  // its document is gone, so it would be created again over the newer one.
  assert.deepEqual((await call('shareProfile', 'me', { records: [first] })).result, { shared: [G], skipped: [] });
  assert.deepEqual(await profilesBy('me'), [second.id], 'the latest (by HLC, as every phone shows it) stays alone');
  // An older record never shared before (another phone of the account, behind): the same.
  const older = profileOf(M.me, 'Mo', {}, 2);
  assert.deepEqual((await call('shareProfile', 'me', { records: [older] })).result, { shared: [G], skipped: [] });
  assert.deepEqual(await profilesBy('me'), [second.id]);
});

// ---- D-211 (founder decision 5, per place): a preview on a place you left stays ----

/** A marked profile record `uid` wrote earlier on place `member` (a place the
 *  account held before it was removed): seeded, as only the Function writes them. */
const oldProfile = (uid, n, member) => seed(`groups/${G}/records/${1000 + n}-0-${D}`, {
  type: '3', hlc: { millis: 1000 + n, counter: 0, device: D }, authorUid: uid, deviceId: D, createdAt: new Date(), profile: true,
  payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes({ op: 'memberProfile', group: G, member, name: 'Mo' }) },
});

test('shareProfile deletes only earlier previews of the same place: one on a place you left stays', async () => {
  const gone = `${1000 + 20}-0-${D}`;
  await oldProfile('me', 20, M.bo); // the caller's preview on another place, older than anything below
  const first = profileOf(M.me, 'Mo', { thumb: THUMB }, 1);
  assert.deepEqual((await call('shareProfile', 'me', { records: [first] })).result, { shared: [G], skipped: [] });
  assert.deepEqual(await profilesBy('me'), [gone, first.id].sort(), 'the old place keeps its preview, the current place has one');
  // A second share on the same place replaces only that place's earlier one.
  const second = profileOf(M.me, 'Mo', { emoji: '\u{1F98A}' }, 3);
  assert.deepEqual((await call('shareProfile', 'me', { records: [second] })).result, { shared: [G], skipped: [] });
  assert.deepEqual(await profilesBy('me'), [gone, second.id].sort(), 'exactly one for the current place, the other place untouched');
  // A retry of the first (lost answer) changes nothing.
  assert.deepEqual((await call('shareProfile', 'me', { records: [first] })).result, { shared: [G], skipped: [] });
  assert.deepEqual(await profilesBy('me'), [gone, second.id].sort());
});

// ---- D-227: a group knows its creator by the place the creator's own first create names ----
//
// One account used on two phones has one participant id (users/{uid}, the first
// phone's) but may have made a group under the other phone's "you". The server
// reads the creator's place from the group's own log: the first member of the
// earliest create op the creator's account wrote (by the server's createdAt, then
// the HLC). groupRoles reports places beside the D-210 participant ids.

const A = 'a1'.repeat(16); // cy's participant id (users/cy), the first phone's
const B = 'b1'.repeat(16); // the "you" cy's other phone made a group under
const X = 'c1'.repeat(16); // Dee's place in that group
const GC = '0c'.repeat(16);

/** A group operation in group `group`'s log, written by `uid` as a phone writes it. */
async function opIn(group, uid, n, blob, createdAt = new Date()) {
  await seed(`groups/${group}/records/${1000 + n}-0-${D}`, {
    type: '3', hlc: { millis: 1000 + n, counter: 0, device: D }, authorUid: uid, deviceId: D, createdAt,
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes(blob) },
  });
}
/** cy's group, made by cy's account with `place` as its first member (and Dee's place). */
async function cyGroup(group = GC, place = B, memberUids = ['cy']) {
  await seed('users/cy', { participantId: A, name: 'Cy' });
  await seed(`groups/${group}`, { name: 'Cy trip', memberUids, createdBy: 'cy', createdAt: new Date() });
  await opIn(group, 'cy', 0, { op: 'create', group, name: 'Cy trip', settlementCurrency: 'INR',
    members: [{ id: place, name: 'Cy' }, { id: X, name: 'Dee' }] });
}
/** An unused invite to place `member` of group `group`, and its token. */
async function inviteIn(group, member, token) {
  await seed(`invites/${createHash('sha256').update(token).digest('hex')}`, { groupId: group, memberId: member, memberName: 'Dee',
    groupName: 'Cy trip', inviterName: 'Cy', createdBy: 'cy', createdAt: new Date(), expiresAt: new Date(Date.now() + 3600000), usedBy: null });
  return token;
}

test('D-227: the creator holds the place its first create names, not its participant id (the report\'s repro)', async () => {
  await cyGroup();
  assert.deepEqual((await call('groupRoles', 'cy', { groupId: GC })).result,
    { owner: A, admins: [], places: { owner: [B], admins: [], you: [B] } }, '`owner` keeps its D-210 meaning');
  assert.deepEqual((await call('shareProfile', 'cy', { records: [profileOf(B, 'Cy', {}, 1, GC)] })).result, { shared: [GC], skipped: [] });
  assert.equal((await read(`groups/${GC}/records/${profileOf(B, 'Cy', {}, 1, GC).id}`)).profile.booleanValue, true);
});

test('D-227: one account, two groups made under two ids: each group knows it by its own place', async () => {
  const G1 = '01'.repeat(16);
  const G2 = '02'.repeat(16);
  await cyGroup(G1, A);
  await cyGroup(G2, B);
  assert.deepEqual((await call('groupRoles', 'cy', { groupId: G1 })).result.places, { owner: [A], admins: [], you: [A] });
  assert.deepEqual((await call('groupRoles', 'cy', { groupId: G2 })).result.places, { owner: [B], admins: [], you: [B] });
  const records = [profileOf(A, 'Cy', {}, 1, G1), profileOf(B, 'Cy', {}, 2, G2)];
  assert.deepEqual((await call('shareProfile', 'cy', { records })).result, { shared: [G1, G2], skipped: [] });
});

test('D-227: only the creator\'s earliest create counts: a member\'s create and a later one of the creator are ignored', async () => {
  // me's phone writes a create naming its own place first, earlier than the creator's (server time and HLC).
  await op('me', -5, { op: 'create', group: G, name: 'Mine', settlementCurrency: 'INR',
    members: [{ id: M.me, name: 'Mo' }, { id: M.ow, name: 'Ola' }] }, new Date(Date.now() - 60000));
  // The creator writes a second create later (server time) naming bo first; its HLC is the earlier one.
  await op('ow', -10, { op: 'create', group: G, name: 'Again', settlementCurrency: 'INR',
    members: [{ id: M.bo, name: 'Bo' }, { id: M.ow, name: 'Ola' }] }, new Date(Date.now() + 60000));
  assert.deepEqual((await call('groupRoles', 'me', { groupId: G })).result,
    { owner: P.ow, admins: [P.ad], places: { owner: [M.ow], admins: [M.ad], you: [M.me] } });
  assert.deepEqual(await call('createInvite', 'me', link(M.ow)), HELD_ERR, 'the creator\'s place is still held');
  assert.ok((await call('createInvite', 'me', link(M.bo))).result.token, 'the later create\'s first member is nobody\'s');
  assert.deepEqual(await call('removeMember', 'ad', remove('ow', { you: null })), { error: 'PERMISSION_DENIED' }, 'still the owner');
});

test('D-227: D-218 holds the creator\'s place when it is not their participant id', async () => {
  await cyGroup();
  const linkIn = (member) => ({ groupId: GC, memberId: member, memberName: 'Dee', groupName: 'Cy trip', inviterName: 'Cy' });
  assert.deepEqual(await call('createInvite', 'cy', linkIn(B)), HELD_ERR, 'the creator\'s place');
  assert.ok((await call('createInvite', 'cy', linkIn(X))).result.token, 'Dee\'s place is free');
  // An invite for B made before the create landed (seeded directly, past refuseHeld) is refused at acceptance now that
  // it has (holderIn returns the creator): what keeps decision 36 from reaching an honest creator once its create is up.
  assert.deepEqual(await call('acceptInvite', 'eve', { token: await inviteIn(GC, B, 'Q'.repeat(43)) }), { error: 'FAILED_PRECONDITION' },
    'another account never joins the creator\'s place');
  assert.deepEqual(strings((await read(`groups/${GC}`)).memberUids), ['cy']);
});

test('D-227: a creator handed over and removed holds nothing; rejoined at another place, only that one', async () => {
  assert.ok((await call('makeOwner', 'ow', roles({ you: P.me }))).result);
  assert.deepEqual((await call('removeMember', 'me', remove('ow', { you: null }))).result, { removed: true });
  assert.deepEqual(await call('createInvite', 'me', link(M.ow)), { error: 'FAILED_PRECONDITION' },
    'D-230 decision 64: the removed creator\'s place is held by nobody, and never invited again');
  assert.ok((await call('acceptInvite', 'ow', { token: await invite(M.bo, 'H'.repeat(43)) })).result, 'ow joins bo\'s place');
  assert.deepEqual((await call('groupRoles', 'ow', { groupId: G })).result,
    { owner: P.me, admins: [P.ad], places: { owner: [M.me], admins: [M.ad], you: [M.bo] } });
  assert.deepEqual(await call('createInvite', 'me', link(M.ow)), { error: 'FAILED_PRECONDITION' },
    'the first place stays nobody\'s after the creator rejoins');
  assert.deepEqual((await call('shareProfile', 'ow', { records: [profileOf(M.ow, 'Ola')] })).result, { shared: [], skipped: [G] });
  assert.deepEqual((await call('shareProfile', 'ow', { records: [profileOf(M.bo, 'Ola', {}, 2)] })).result, { shared: [G], skipped: [] });
});

test('D-227: groupRoles lists each role\'s places; a joined admin\'s is its invite\'s; a contested place is skipped', async () => {
  assert.deepEqual((await call('groupRoles', 'ow', { groupId: G })).result,
    { owner: P.ow, admins: [P.ad], places: { owner: [M.ow], admins: [M.ad], you: [M.ow] } });
  assert.ok((await call('setAdmin', 'ow', roles({ you: P.me, on: true }))).result);
  assert.deepEqual((await call('groupRoles', 'sam', { groupId: G })).result,
    { owner: P.ow, admins: [P.ad, P.me], places: { owner: [M.ow], admins: [M.ad, M.me], you: [M.sam] } });
  // Two accounts joined to sam's place (a state acceptInvite refuses to make): neither holds it, and nothing fails.
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam', 'eve'], createdBy: 'ow', admins: ['ad', 'me'] });
  await joined('eve', M.sam);
  assert.deepEqual((await call('groupRoles', 'sam', { groupId: G })).result.places, { owner: [M.ow], admins: [M.ad, M.me], you: [] });
  assert.deepEqual((await call('groupRoles', 'eve', { groupId: G })).result.places.you, []);
});

test('D-227: a role change names a holder by its own claim\'s "you" or its creator place, never by another member\'s claim', async () => {
  const Q = '7a'.repeat(16); // the "you" sam's phone claims with, not sam's participant id
  const R = '7b'.repeat(16);
  await op('sam', 41, { op: 'claim', group: G, member: M.sam, you: Q, devices: [] });
  assert.deepEqual((await call('setAdmin', 'ow', roles({ member: M.sam, you: Q, on: true }))).result, { admins: [P.ad, P.sam] });
  // A claim me's phone wrote on sam's place is not sam's word.
  await op('me', 42, { op: 'claim', group: G, member: M.sam, you: R, devices: [] });
  assert.deepEqual(await call('setAdmin', 'ow', roles({ member: M.sam, you: R, on: false })), { error: 'NOT_FOUND' });
  // sam's own claim on another place is no "you" for this one.
  await op('sam', 43, { op: 'claim', group: G, member: M.bo, you: R, devices: [] });
  assert.deepEqual(await call('setAdmin', 'ow', roles({ member: M.sam, you: R, on: false })), { error: 'NOT_FOUND' });
  // A joiner's own place is no "you" for it: only a creator's place names its holder.
  assert.deepEqual(await call('setAdmin', 'ow', roles({ member: M.sam, you: M.sam, on: false })), { error: 'NOT_FOUND' },
    'a joiner named by its invite place');
  assert.deepEqual(strings((await read(`groups/${G}`)).admins), ['ad', 'sam'], 'nothing changed by the refused calls');
  // The creator's place names its holder: cy (participant id A) made the group as B.
  await cyGroup(GC, B, ['cy', 'dee']);
  await seed('users/dee', { participantId: '7c'.repeat(16), name: 'Dee' });
  await seed(`invites/joined-dee-${GC.slice(0, 4)}`, { groupId: GC, memberId: X, usedBy: 'dee', usedAt: new Date() });
  const on = (uid, member, you, more = {}) => call('setAdmin', uid, { groupId: GC, groupName: 'Cy trip', member, you, ...more });
  assert.deepEqual((await call('makeOwner', 'cy', { groupId: GC, groupName: 'Cy trip', member: X, you: '7c'.repeat(16) })).result,
    { owner: '7c'.repeat(16) });
  assert.deepEqual(await on('dee', B, X, { on: false }), { error: 'NOT_FOUND' }, 'Dee\'s place is not cy\'s word');
  assert.deepEqual((await on('dee', B, B, { on: false })).result, { admins: [] }, 'the creator place names cy');
  assert.deepEqual(strings((await read(`groups/${GC}`)).admins), []);
});

test('D-227: a group whose create has not reached the cloud yet has empty places, never an error', async () => {
  await seed('users/cy', { participantId: A, name: 'Cy' });
  await seed(`groups/${GC}`, { name: 'Cy trip', memberUids: ['cy'], createdBy: 'cy', createdAt: new Date() });
  assert.deepEqual((await call('groupRoles', 'cy', { groupId: GC })).result,
    { owner: A, admins: [], places: { owner: [], admins: [], you: [] } });
});

// ---- D-227 fix wave (security review of T1) ----

test('D-227 decision 36: a create written after another account joined its first member\'s place gives the creator nothing there', async () => {
  // cy (a modified client) makes the group, writes no create, invites place X; dee joins it; only then cy's first create names X first.
  const DEE = '7c'.repeat(16);
  await seed('users/cy', { participantId: A, name: 'Cy' });
  await seed('users/dee', { participantId: DEE, name: 'Dee' });
  await seed(`groups/${GC}`, { name: 'Cy trip', memberUids: ['cy', 'dee'], createdBy: 'cy', createdAt: new Date() });
  await seed(`invites/joined-dee-${GC.slice(0, 4)}`, { groupId: GC, memberId: X, usedBy: 'dee', usedAt: new Date(Date.now() - 60000) });
  await opIn(GC, 'cy', 0, { op: 'create', group: GC, name: 'Cy trip', settlementCurrency: 'INR',
    members: [{ id: X, name: 'Dee' }, { id: B, name: 'Cy' }] });
  assert.deepEqual((await call('groupRoles', 'dee', { groupId: GC })).result.places, { owner: [], admins: [], you: [X] }, 'the invite wins');
  assert.deepEqual((await call('groupRoles', 'cy', { groupId: GC })).result.places.you, [], 'the creator holds nothing there');
  // The creator gains no power over the place.
  assert.deepEqual((await call('shareProfile', 'cy', { records: [profileOf(X, 'Cy', {}, 1, GC)] })).result, { shared: [], skipped: [GC] });
  assert.deepEqual(await call('setAdmin', 'cy', { groupId: GC, groupName: 'Cy trip', member: X, you: X, on: true }), { error: 'NOT_FOUND' },
    'the place is no "you" of the creator\'s');
  // Dee can be made an admin, shares a profile, and stays removable.
  assert.deepEqual((await call('setAdmin', 'cy', { groupId: GC, groupName: 'Cy trip', member: X, you: DEE, on: true })).result, { admins: [DEE] });
  assert.deepEqual((await call('shareProfile', 'dee', { records: [profileOf(X, 'Dee', {}, 2, GC)] })).result, { shared: [GC], skipped: [] });
  const records = [rec('3', { op: 'removeMember', group: GC, member: X }, 5)];
  assert.deepEqual((await call('removeMember', 'cy', { groupId: GC, member: X, you: DEE, memberName: 'Dee', groupName: 'Cy trip', records })).result,
    { removed: true });
  assert.deepEqual(strings((await read(`groups/${GC}`)).memberUids), ['cy']);
});

test('D-227 decision 37: a creator once removed from its place never holds it as creator again, even after an addMember', async () => {
  const pause = () => new Promise((r) => setTimeout(r, 50));
  assert.ok((await call('makeOwner', 'ow', roles({ you: P.me }))).result);
  assert.deepEqual((await call('removeMember', 'me', remove('ow', { you: null }))).result, { removed: true });
  assert.ok((await call('acceptInvite', 'ow', { token: await invite(M.bo, 'J'.repeat(43)) })).result, 'ow rejoins at bo\'s place');
  await pause();
  await op('ow', 50, { op: 'addMember', group: G, member: { id: M.ow, name: 'Ola' } }); // ow's phone adds its old place back
  assert.deepEqual((await call('groupRoles', 'ow', { groupId: G })).result.places.you, [M.bo], 'only the invite\'s place');
  // D-230 decision 64 (A9): a place once removed is never invited again, added back or not.
  assert.deepEqual(await call('createInvite', 'me', link(M.ow)), { error: 'FAILED_PRECONDITION' }, 'the old place is nobody\'s, for good');
  assert.deepEqual(await call('acceptInvite', 'eve', { token: await invite(M.ow, 'K'.repeat(43)) }), { error: 'FAILED_PRECONDITION' });
  // The added-back placeholder holds nobody: removing it again removes no account (ow stays, through bo's place).
  assert.deepEqual((await call('removeMember', 'me', remove('ow', { you: null, records: [removalAt(M.ow, 9)] }))).result, { removed: true });
  assert.ok(strings((await read(`groups/${G}`)).memberUids).includes('ow'), 'ow is still a member');
});

test('D-227: a creator no longer in the group holds nothing as creator, with no removal record', async () => {
  // A creator purged before D-210 handed groups on: out of memberUids, no removeMember of its place.
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ad', 'me', 'sam'], createdBy: 'ow', admins: ['ad'] });
  assert.ok((await call('createInvite', 'me', link(M.ow))).result.token, 'the creator place is free');
  assert.deepEqual((await call('groupRoles', 'me', { groupId: G })).result.places,
    { owner: [M.ad], admins: [], you: [M.me] }, 'the seat went to the first admin');
});

test('D-227: a new admin is shown the open requests, never one about themselves, however they were named', async () => {
  const Q = '7a'.repeat(16); // sam's phone's claim id, not sam's participant id
  await op('sam', 41, { op: 'claim', group: G, member: M.sam, you: Q, devices: [] });
  // Two open requests, written as requestRemoval writes them, without their notices: one about sam (stored
  // under sam's participant id), one about bo.
  for (const [member, you, memberName] of [[M.sam, P.sam, 'Sam'], [M.bo, null, 'Bo']]) {
    await seed(`groups/${G}/requests/${member}`, { member, you, memberName, askerUid: 'me', askerName: 'Mo', at: new Date() });
  }
  assert.ok((await call('setAdmin', 'ow', roles({ member: M.sam, you: Q, on: true }))).result, 'sam named by the claim id');
  const ids = (await notices('sam')).filter((n) => n.kind.stringValue === 'request').map((n) => n.id);
  assert.deepEqual(ids, [`request-${G}-${M.bo}`], 'the request about bo, not the one about sam');
});

// ---- D-229: shareAliases ----
//
// One account (al) on two phones made group GA under place XA, then one phone
// adopted the other's "you" (Y) and wrote records paid by Y, which bo's phone
// cannot place. shareAliases writes a placeAlias op marked `alias: true` after
// the server checks that XA is al's place and Y is al's own id and nobody
// else's. bo is the third member; cy is a member who attacks.

const GA = 'a5'.repeat(16);
const XA = 'a7'.repeat(16); // al's place (the creator place)
const BP = 'b7'.repeat(16); // bo's place
const CP = 'c7'.repeat(16); // cy's place
const X2 = 'd7'.repeat(16); // a second place al joins by invite
const Y = 'e7'.repeat(16); // the "you" al's other phone adopted
const PID = { al: XA, bo: 'b8'.repeat(16), cy: 'c8'.repeat(16) };

/** A personal record in `uid`'s own log, as a phone uploads it. */
async function personal(uid, n, blob) {
  await seed(`users/${uid}/records/${3000 + n}-0-${D}`, {
    type: '3', hlc: { millis: 3000 + n, counter: 0, device: D }, authorUid: uid, deviceId: D, createdAt: new Date(),
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes(blob) },
  });
}
const youIn = (uid, n, id) => personal(uid, n, { op: 'you', id });
/** The server's record of `uid` joining place `member` of GA. */
const joinedGA = (uid, member) => seed(`invites/joined-${uid}-${member.slice(0, 4)}`, {
  groupId: GA, memberId: member, memberName: uid, groupName: 'Al trip', inviterName: 'Al', createdBy: 'al',
  createdAt: new Date(), expiresAt: new Date(Date.now() + 3600000), usedBy: uid, usedAt: new Date(),
});
/** al's group: al made it as XA; bo and cy joined BP and CP and claimed them; al's log holds XA and Y; an expense paid by Y. */
async function alGroup(memberUids = ['al', 'bo', 'cy'], more = {}) {
  for (const uid of ['al', 'bo', 'cy']) await seed(`users/${uid}`, { participantId: PID[uid], name: uid });
  await seed(`groups/${GA}`, { name: 'Al trip', memberUids, createdBy: 'al', createdAt: new Date(), ...more });
  await opIn(GA, 'al', 0, { op: 'create', group: GA, name: 'Al trip', settlementCurrency: 'INR',
    members: [{ id: XA, name: 'Al' }, { id: BP, name: 'Bo' }, { id: CP, name: 'Cy' }] });
  await joinedGA('bo', BP);
  await joinedGA('cy', CP);
  await opIn(GA, 'bo', 1, { op: 'claim', group: GA, member: BP, you: PID.bo, devices: [] });
  await opIn(GA, 'cy', 2, { op: 'claim', group: GA, member: CP, you: PID.cy, devices: [] });
  await seed(`groups/${GA}/records/1003-0-${D}`, {
    type: '1', hlc: { millis: 1003, counter: 0, device: D }, authorUid: 'al', deviceId: D, createdAt: new Date(),
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes({ group: GA, payerId: Y, amountMinor: 1000 }) },
  });
  await youIn('al', 0, XA);
  await youIn('al', 1, Y);
  await youIn('bo', 0, PID.bo);
  await youIn('cy', 0, PID.cy);
}
/** A placeAlias record as the phones build it (core's GroupOp::PlaceAlias). */
const aliasOf = (member, you, n = 1, group = GA) => rec('3', { op: 'placeAlias', group, member, you }, n);
/** The alias documents in GA: [{id, member, you, author}]. */
const aliasesIn = async (group = GA) => (await list(`groups/${group}/records`))
  .filter((d) => d.fields.alias?.booleanValue === true)
  .map((d) => {
    const op = JSON.parse(Buffer.from(d.fields.payload.mapValue.fields.blob.bytesValue, 'base64').toString('utf8'));
    return { id: idOf(d), member: op.member, you: op.you, author: d.fields.authorUid.stringValue };
  });
/** A document as `uid` reads it through the rules: { status, fields }. */
async function readAs(uid, path) {
  const r = await fetch(`${FS}/v1/${DOCS}/${path}`, { headers: { authorization: `Bearer ${idToken(uid)}` } });
  return { status: r.status, fields: r.status === 200 ? (await r.json()).fields : null };
}
const SECOND_PHONE = { phone: 'second' };
const share = (uid, records, claims) => call('shareAliases', uid, { records }, claims);

test('D-229 shareAliases: al\'s other "you" reads as al\'s place, marked, and bo reads it', async () => {
  await alGroup();
  const r = aliasOf(XA, Y);
  assert.deepEqual((await share('al', [r], SECOND_PHONE)).result, { shared: [r.id], skipped: [] });
  const seen = await readAs('bo', `groups/${GA}/records/${r.id}`);
  assert.equal(seen.status, 200, 'bo reads it through the rules');
  assert.equal(seen.fields.alias.booleanValue, true);
  assert.equal(seen.fields.authorUid.stringValue, 'al');
  assert.equal(seen.fields.type.stringValue, '3');
  assert.equal(seen.fields.payload.mapValue.fields.blob.bytesValue, r.payload.blob, 'the blob\'s bytes, as the phone built them');
  assert.ok(seen.fields.createdAt.timestampValue, 'the server\'s time');
  assert.equal(seen.fields.removal, undefined, 'never a removal');
  assert.equal(seen.fields.profile, undefined, 'never a profile');
  assert.equal((await readAs('cy', `groups/${GA}/records/${r.id}`)).status, 200);
  assert.deepEqual(await aliasesIn(), [{ id: r.id, member: XA, you: Y, author: 'al' }]);
});

test('D-229 shareAliases: the same alias again is shared and writes nothing (a retry, or the other phone)', async () => {
  await alGroup();
  const r = aliasOf(XA, Y);
  assert.deepEqual((await share('al', [r])).result, { shared: [r.id], skipped: [] });
  assert.deepEqual((await share('al', [r], SECOND_PHONE)).result, { shared: [r.id], skipped: [] }, 'the same record');
  const again = aliasOf(XA, Y, 2);
  assert.deepEqual((await share('al', [again])).result, { shared: [again.id], skipped: [] }, 'another record, the same alias');
  assert.deepEqual(await aliasesIn(), [{ id: r.id, member: XA, you: Y, author: 'al' }], 'one document');
});

test('D-229 shareAliases: an id in another member\'s own log is contested: nobody gets it, and the answer is a plain skip', async () => {
  await alGroup();
  const before = await recordsOf(GA);
  const nobodys = aliasOf(XA, 'ab'.repeat(16), 3);
  assert.deepEqual((await share('al', [nobodys])).result, { shared: [], skipped: [nobodys.id] }, 'an id in nobody\'s log, not even al\'s');
  const asked = aliasOf(CP, Y, 1);
  assert.deepEqual((await share('cy', [asked])).result, { shared: [], skipped: [asked.id] }, 'not in cy\'s own log');
  await youIn('cy', 1, Y); // cy copies al's id into its own log
  assert.deepEqual((await share('cy', [asked])).result, { shared: [], skipped: [asked.id] }, 'al\'s log holds it too');
  const al = aliasOf(XA, Y, 2);
  assert.deepEqual((await share('al', [al])).result, { shared: [], skipped: [al.id] }, 'contested: a denial, never a takeover');
  assert.deepEqual(await recordsOf(GA), before, 'no alias for Y exists');
  assert.deepEqual(await aliasesIn(), []);
});

test('D-229 shareAliases: never another member\'s place, a place id, a claimed id, or an id already aliased elsewhere', async () => {
  await alGroup();
  const before = await recordsOf(GA);
  const skip = async (records, why) => assert.deepEqual((await share('al', records)).result,
    { shared: [], skipped: records.map((r) => r.id) }, why);
  await skip([aliasOf(BP, Y)], 'bo\'s place is not al\'s');
  await youIn('al', 2, BP); // a modified client copies bo's place id into al's own log
  await skip([aliasOf(XA, BP)], 'a place id');
  await youIn('al', 3, PID.bo); // and the "you" bo's claim names
  await skip([aliasOf(XA, PID.bo)], 'an id bo claimed with');
  // A claim another account wrote whose "you" is in no member's log (that account was deleted, its log with it).
  const Q = 'f7'.repeat(16);
  await opIn(GA, 'gone', 4, { op: 'claim', group: GA, member: CP, you: Q, devices: [] });
  // D-235 round 2: the purge keeps a deleted account as `departed`, so the log is still read by its author.
  await seed(`groups/${GA}`, { name: 'Al trip', memberUids: ['al', 'bo', 'cy'], createdBy: 'al', createdAt: new Date(), departed: ['gone'] });
  await youIn('al', 4, Q);
  await skip([aliasOf(XA, Q)], 'an id another account claimed with');
  assert.deepEqual(await recordsOf(GA), [...before, `1004-0-${D}`], 'nothing written but the seeded claim');
  // al also holds X2 (added, joined by invite): Y aliased onto XA is never aliased onto X2 too.
  await opIn(GA, 'al', 5, { op: 'addMember', group: GA, member: { id: X2, name: 'Al 2' } });
  await joinedGA('al', X2);
  const first = aliasOf(XA, Y, 1);
  const claimed = aliasOf(XA, Q, 2);
  assert.deepEqual((await share('al', [first, claimed])).result, { shared: [first.id], skipped: [claimed.id] }, 'one call, two answers');
  await skip([aliasOf(X2, Y, 3)], 'a second place for one id');
  assert.deepEqual(await aliasesIn(), [{ id: first.id, member: XA, you: Y, author: 'al' }]);
});

test('D-229 shareAliases: a stranger, a removed member, a removed place and malformed records', async () => {
  await alGroup();
  await seed('users/eve', { participantId: P.eve, name: 'Eve' });
  await youIn('eve', 0, Y);
  const r = aliasOf(XA, Y);
  assert.deepEqual((await share('eve', [r])).result, { shared: [], skipped: [r.id] }, 'not a member');
  assert.deepEqual(await share(null, [r]), { error: 'UNAUTHENTICATED' });
  for (const [records, why] of [
    [[aliasOf(XA, XA)], 'you == member'],
    [[rec('3', { op: 'memberProfile', group: GA, member: XA, name: 'al' })], 'another op'],
    [Array.from({ length: 51 }, (_, i) => aliasOf(XA, (i + 1).toString(16).padStart(32, '0'), i + 1)), 'fifty-one'],
    [[aliasOf(XA, Y, 1), aliasOf(X2, Y, 2)], 'one id twice in a call'],
    [[], 'none'],
  ]) {
    assert.deepEqual(await share('al', records), { error: 'INVALID_ARGUMENT' }, why);
  }
  assert.deepEqual(await call('shareAliases', 'al', {}), { error: 'INVALID_ARGUMENT' }, 'no records');
  assert.deepEqual(await aliasesIn(), [], 'nothing written');
  // al's place removed (by an admin, marked): al holds it no more.
  await seed(`groups/${GA}/records/1009-0-${D}`, {
    type: '3', hlc: { millis: 1009, counter: 0, device: D }, authorUid: 'bo', deviceId: D, createdAt: new Date(), removal: true,
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes({ op: 'removeMember', group: GA, member: XA }) },
  });
  assert.deepEqual((await share('al', [r])).result, { shared: [], skipped: [r.id] }, 'a removed place');
  // A place al holds by its invite that the log never made, and one the log made whose removal is marked
  // (adoptRemovals, while al was alone, after accepting its own invite): al holds each, neither is a place to alias onto.
  const ghost = '8e'.repeat(16);
  await joinedGA('al', ghost);
  assert.deepEqual((await share('al', [aliasOf(ghost, Y, 2)])).result, { shared: [], skipped: [aliasOf(ghost, Y, 2).id] }, 'no log op made it');
  await opIn(GA, 'al', 5, { op: 'addMember', group: GA, member: { id: X2, name: 'Al 2' } });
  await joinedGA('al', X2);
  await seed(`groups/${GA}/records/1010-0-${D}`, {
    type: '3', hlc: { millis: 1010, counter: 0, device: D }, authorUid: 'al', deviceId: D, createdAt: new Date(Date.now() + 1000), removal: true,
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes({ op: 'removeMember', group: GA, member: X2 }) },
  });
  assert.deepEqual((await share('al', [aliasOf(X2, Y, 3)])).result, { shared: [], skipped: [aliasOf(X2, Y, 3).id] }, 'a removed place al still holds');
  // al removed from the group (former): skipped, whatever its log holds.
  await alGroup(['bo', 'cy'], { former: { al: { at: new Date(), by: 'bo' } } });
  assert.deepEqual((await share('al', [r])).result, { shared: [], skipped: [r.id] }, 'a removed member');
  assert.deepEqual(await aliasesIn(), []);
});

test('D-229 shareAliases: a removed member\'s own id stays theirs: a co-member who copies it gets nothing', async () => {
  // bo's other phone's "you" (Z) paid in GA; bo was removed before any alias. cy copies Z into its own log.
  const Z = '9e'.repeat(16);
  await alGroup(['al', 'cy'], { former: { bo: { at: new Date(), by: 'al' } } });
  await youIn('bo', 1, Z);
  await youIn('cy', 1, Z);
  const r = aliasOf(CP, Z);
  assert.deepEqual((await share('cy', [r])).result, { shared: [], skipped: [r.id] });
  assert.deepEqual(await aliasesIn(), []);
});

// ---- D-229 fix wave (security review of T2) ----

test('D-229 decision 47: no member pre-aliases another account\'s participant id, even before that account joins', async () => {
  // The review's attack: cy knows vee's participant id V (a claim in a group they share), copies it into its own log
  // and aliases it onto its own place before vee joins GA; then vee joins DV and claims it with V.
  await alGroup();
  const V = '7e'.repeat(16);
  const DV = '6e'.repeat(16);
  await seed('users/vee', { participantId: V, name: 'Vee' });
  await opIn(GA, 'al', 6, { op: 'addMember', group: GA, member: { id: DV, name: 'Vee' } });
  await youIn('cy', 1, V);
  const early = aliasOf(CP, V, 1);
  assert.deepEqual((await share('cy', [early])).result, { shared: [], skipped: [early.id] }, 'not a member yet: still refused');
  await seed(`groups/${GA}`, { name: 'Al trip', memberUids: ['al', 'bo', 'cy', 'vee'], createdBy: 'al', createdAt: new Date() });
  await joinedGA('vee', DV);
  await opIn(GA, 'vee', 7, { op: 'claim', group: GA, member: DV, you: V, devices: [] });
  assert.deepEqual(await aliasesIn(), [], 'no alias for V exists when vee arrives');
  // The caller's own participant id is never "another account's": D-227's case (a group made under the other
  // phone's "you", records paid by the account's participant id) is the repair itself.
  const A2 = '5a'.repeat(16);
  await seed('users/al', { participantId: A2, name: 'al' });
  await youIn('al', 5, A2);
  const own = aliasOf(XA, A2, 2);
  assert.deepEqual((await share('al', [own])).result, { shared: [own.id], skipped: [] });
});

test('D-229 decision 48: a daily quota of alias records, charged before anything is read; RESOURCE_EXHAUSTED past it', async () => {
  await alGroup();
  const today = new Date().toISOString().slice(0, 10);
  await seed('limits/al', { day: today, searches: 0, hashes: 0, invites: 0, checks: 0, aliases: 99 });
  assert.deepEqual(await share('al', [aliasOf(XA, Y, 1), aliasOf(XA, 'ab'.repeat(16), 2)]), { error: 'RESOURCE_EXHAUSTED' },
    'two records past 99 of 100');
  assert.deepEqual(await aliasesIn(), [], 'nothing written');
  const r = aliasOf(XA, Y, 1);
  assert.deepEqual((await share('al', [r])).result, { shared: [r.id], skipped: [] }, 'the hundredth');
  assert.equal(Number((await read('limits/al')).aliases.integerValue), 100);
  assert.deepEqual(await share('al', [aliasOf(XA, Y, 3)]), { error: 'RESOURCE_EXHAUSTED' }, 'the hundred and first, even a retry');
  // A skipped record costs the quota too (a probe is never free).
  await seed('limits/cy', { day: today, aliases: 100 });
  assert.deepEqual(await share('cy', [aliasOf(CP, 'ab'.repeat(16), 4)]), { error: 'RESOURCE_EXHAUSTED' });
});

test('D-229 review M3: a record id already holding another document is skipped, never counted shared', async () => {
  await alGroup();
  const r = aliasOf(XA, Y, 1);
  await seed(`groups/${GA}/records/${r.id}`, {
    type: '1', hlc: r.hlc, authorUid: 'bo', deviceId: D, createdAt: new Date(),
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes({ group: GA, payerId: BP, amountMinor: 5 }) },
  });
  assert.deepEqual((await share('al', [r])).result, { shared: [], skipped: [r.id] });
  assert.deepEqual(await aliasesIn(), []);
  assert.equal((await read(`groups/${GA}/records/${r.id}`)).authorUid.stringValue, 'bo', 'bo\'s record untouched');
});

test('D-229 review M4: a place held by an accepted invite (decision 36) aliases too', async () => {
  await alGroup();
  const Z2 = '4e'.repeat(16); // the "you" bo's other phone adopted
  await youIn('bo', 1, Z2);
  const r = aliasOf(BP, Z2, 1);
  assert.deepEqual((await share('bo', [r])).result, { shared: [r.id], skipped: [] });
  assert.deepEqual(await aliasesIn(), [{ id: r.id, member: BP, you: Z2, author: 'bo' }]);
});

// ---- D-229 fix wave (whole-branch review I1, decision 52) ----
//
// One account holding two present places in a group makes its two claims
// contest each other (core's decision 44): its history reads as unknown and
// its own phones turn the group read-only. acceptInvite therefore refuses an
// account that already holds a place in the invite's group (placesOf), before
// any write and without spending the invite, with `details.member` so the
// phones can say "You're already in this group." Accepting another invite for
// the place it already holds stays allowed (D-210 fix 4), and someone removed
// holds no place, so a rejoin (decision 49) is never refused.

/** acceptInvite as the phones see it: the status, message and details. */
async function accept(uid, token, records = null) {
  await approve(uid);
  const r = await fetch(`${FN}/acceptInvite`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${idToken(uid)}` },
    body: JSON.stringify({ data: records ? { token, records } : { token } }),
  });
  const body = await r.json();
  notGated('acceptInvite', uid, body);
  return body.error ? { status: body.error.status, message: body.error.message, details: body.error.details ?? null }
    : { result: body.result };
}
const MEMBER_ERR = { status: 'ALREADY_EXISTS', message: 'You\'re already in this group.', details: { member: true } };

test('D-229 decision 52: an account already in the group taps a second invite: refused, the invite unused, nothing written', async () => {
  const token = await invite(M.bo, 'F'.repeat(43));
  const hash = createHash('sha256').update(token).digest('hex');
  await seed('users/sam/invites/copy', { token, groupId: G, groupName: 'Goa trip' });
  await seed('users/ow/invites/copy', { token, groupId: G, groupName: 'Goa trip' });
  const before = { group: await read(`groups/${G}`), invites: await snapshotOf('invites'), records: await snapshotOf(`groups/${G}/records`) };
  // sam holds a place through an accepted invite; ow holds the creator place.
  for (const uid of ['sam', 'ow']) {
    assert.deepEqual(await accept(uid, token), MEMBER_ERR, uid);
    assert.equal((await read(`invites/${hash}`)).usedBy.nullValue, null, `${uid}: the invite stays unused`);
    assert.ok(await read(`users/${uid}/invites/copy`), `${uid}: the in-app copy stays`);
  }
  assert.deepEqual(await read(`groups/${G}`), before.group, 'membership untouched');
  assert.deepEqual(await snapshotOf('invites'), before.invites, 'no invite written');
  assert.deepEqual(await snapshotOf(`groups/${G}/records`), before.records, 'no record written');
  // A place held by another account still answers the held refusal, unchanged, with no details.
  assert.deepEqual(await accept('sam', await invite(M.me, 'G'.repeat(43))),
    { status: 'FAILED_PRECONDITION', message: 'Someone has already joined as this person.', details: null });
  // The invite was not spent: someone not in the group still joins with it.
  assert.deepEqual((await accept('eve', token)).result, { groupId: G, memberId: M.bo, groupName: 'Goa trip' });
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam', 'eve']);
});

test('D-229 decision 52: someone removed and invited again (decision 49\'s rejoin) is not refused', async () => {
  assert.deepEqual((await call('removeMember', 'ad', remove('sam', { records: [removalAt(M.sam, 1)] }))).result, { removed: true });
  const K = '6c'.repeat(16); // the new place the inviter adds for sam
  await op('ad', 11, { op: 'addMember', group: G, member: { id: K, name: 'Sam' } });
  assert.deepEqual((await accept('sam', await invite(K, 'H'.repeat(43)))).result, { groupId: G, memberId: K, groupName: 'Goa trip' });
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam']);
  // Back in, sam holds K: a further invite to another place is now refused.
  assert.deepEqual(await accept('sam', await invite(M.bo, 'J'.repeat(43))), MEMBER_ERR);
});

// ---- D-230: held places (sharePlaces, the join-time mark) ----
//
// A heldPlace op "in group G, place `member` is held by id `you`", marked
// `held: true` by the server after it checks that the caller holds the place
// (holderIn) and owns the id (its participant id or its own log, nobody
// else's). Core takes the latest marked one per member as final (CLOUD-SECURITY
// G-08). al's group (alGroup) again: al holds XA as creator, bo BP and cy CP by
// invite.

/** A heldPlace record as the phones build it, stamped at the fixture's far-past clock (a stale clock). */
const heldOf = (member, you, n = 1, group = GA) => rec('3', { op: 'heldPlace', group, member, you }, n);
/** A heldPlace record of `group` stamped at the server's now plus `n` ms (an honest clock). */
function heldNowIn(group, member, you, n = 1) {
  const r = heldOf(member, you, n, group);
  const millis = Date.now() + n;
  return { ...r, id: `${millis}-0-${D}`, hlc: { millis, counter: 0, device: D } };
}
const heldNow = (member, you, n = 1, group = GA) => heldNowIn(group, member, you, n);
/** The held documents in `group`, in id order: [{id, member, you, author, hlc, deviceId}]. Decision 54 as reworded
 *  (A10): the id and hlc are the server's stamp, never the phone's. */
const heldDocs = async (group = GA) => (await list(`groups/${group}/records`))
  .filter((d) => d.fields.held?.booleanValue === true)
  .map((d) => {
    const op = JSON.parse(Buffer.from(d.fields.payload.mapValue.fields.blob.bytesValue, 'base64').toString('utf8'));
    const h = d.fields.hlc.mapValue.fields;
    return { id: idOf(d), member: op.member, you: op.you, author: d.fields.authorUid.stringValue,
      hlc: { millis: Number(h.millis.integerValue), counter: Number(h.counter.integerValue), device: h.device.stringValue },
      deviceId: d.fields.deviceId.stringValue };
  });
/** The held documents in `group`: [{member, you, author}], in id order (the server's stamp). */
const heldIn = async (group = GA) => (await heldDocs(group)).map(({ member, you, author }) => ({ member, you, author }));
/** The same, as sorted [member, you, author] triples: marks written in one call share a millisecond. */
const heldSet = async (group = GA) => (await heldIn(group)).map((h) => [h.member, h.you, h.author]).sort();
const place = (uid, records, claims) => call('sharePlaces', uid, { records }, claims);

test('D-230 sharePlaces: al marks its creator place, bo its invited place; marked, readable, nothing else written', async () => {
  await alGroup();
  const own = heldNow(XA, XA, 1);
  assert.deepEqual((await place('al', [own])).result, { shared: [own.id], skipped: [] });
  const [stored] = await heldDocs();
  const seen = await readAs('cy', `groups/${GA}/records/${stored.id}`);
  assert.equal(seen.status, 200, 'cy reads it through the rules');
  assert.equal(seen.fields.held.booleanValue, true);
  assert.equal(seen.fields.authorUid.stringValue, 'al');
  assert.equal(seen.fields.type.stringValue, '3');
  assert.equal(seen.fields.payload.mapValue.fields.blob.bytesValue, own.payload.blob, 'the blob\'s bytes, as the phone built them');
  assert.ok(seen.fields.createdAt.timestampValue, 'the server\'s time');
  assert.equal(seen.fields.alias, undefined, 'never an alias');
  assert.equal(seen.fields.removal, undefined, 'never a removal');
  assert.equal(seen.fields.profile, undefined, 'never a profile');
  const bos = heldNow(BP, PID.bo, 2);
  assert.deepEqual((await place('bo', [bos])).result, { shared: [bos.id], skipped: [] });
  assert.deepEqual(await heldIn(), [{ member: XA, you: XA, author: 'al' }, { member: BP, you: PID.bo, author: 'bo' }]);
  // Again: shared, nothing written. The other phone's id for the same place: written (core takes the latest).
  const again = heldNow(XA, XA, 3);
  assert.deepEqual((await place('al', [again])).result, { shared: [again.id], skipped: [] }, 'the same (member, you): already done');
  assert.equal((await heldIn()).length, 2, 'nothing written');
  const other = heldNow(XA, Y, 4);
  assert.deepEqual((await place('al', [other], SECOND_PHONE)).result, { shared: [other.id], skipped: [] }, 'al\'s other id');
  assert.deepEqual((await heldIn()).map((h) => [h.member, h.you]), [[XA, XA], [BP, PID.bo], [XA, Y]]);
});

test('D-230 sharePlaces: never another member\'s place, an id not yours, another account\'s participant id, a copied id, a raw place id held by invite, a removed place', async () => {
  await alGroup();
  const skip = async (uid, records, why) => assert.deepEqual((await place(uid, records)).result,
    { shared: [], skipped: records.map((r) => r.id) }, why);
  await skip('al', [heldNow(BP, XA, 1)], 'bo\'s place');
  await skip('al', [heldNow(XA, 'ab'.repeat(16), 2)], 'an id in nobody\'s log');
  await youIn('al', 2, PID.bo); // al's modified phone copies bo's participant id into al's own log
  await skip('al', [heldNow(XA, PID.bo, 3)], 'bo\'s participant id, even copied');
  // vee is no member and has no log; only the participant-id query knows V is vee's.
  const V = '7e'.repeat(16);
  await seed('users/vee', { participantId: V, name: 'Vee' });
  await youIn('al', 3, V);
  await skip('al', [heldNow(XA, V, 4)], 'another account\'s participant id, that account in no group');
  await youIn('cy', 1, Y); // cy copies al's other id
  await skip('al', [heldNow(XA, Y, 5)], 'contested by a copy: a denial');
  await skip('cy', [heldNow(CP, Y, 6)], 'and cy does not get it either');
  // A place al holds by its invite that no log op made: no place to hold.
  const ghost = '8e'.repeat(16);
  await joinedGA('al', ghost);
  await skip('al', [heldNow(ghost, XA, 10)], 'no log op made it');
  await youIn('bo', 1, BP); // bo's modified phone copies its place id into its own log
  await skip('bo', [heldNow(BP, BP, 8)], 'a raw place id is "you" only for the place its creator holds');
  // bo's place removed (by an admin, marked): bo holds it by invite still, but it is no place to hold.
  await seed(`groups/${GA}/records/1009-0-${D}`, {
    type: '3', hlc: { millis: 1009, counter: 0, device: D }, authorUid: 'al', deviceId: D, createdAt: new Date(), removal: true,
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes({ op: 'removeMember', group: GA, member: BP }) },
  });
  // bo's other phone's id (al copied PID.bo above, so a removed place is tested with an id only bo's log holds).
  const Z = '9e'.repeat(16);
  await youIn('bo', 2, Z);
  await skip('bo', [heldNow(BP, Z, 9)], 'a removed place');
  assert.deepEqual(await heldIn(), []);
});

test('D-230 sharePlaces: a stranger, malformed records, the daily quota', async () => {
  await alGroup();
  const E8 = 'e8'.repeat(16);
  await seed('users/eve', { participantId: E8, name: 'Eve' });
  await youIn('eve', 0, E8);
  const eves = heldNow(XA, E8, 1);
  assert.deepEqual((await place('eve', [eves])).result, { shared: [], skipped: [eves.id] }, 'not a member');
  assert.deepEqual(await place('al', [rec('3', { op: 'placeAlias', group: GA, member: XA, you: Y })]), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await place('al', []), { error: 'INVALID_ARGUMENT' });
  assert.deepEqual(await call('sharePlaces', 'al', {}), { error: 'INVALID_ARGUMENT' }, 'no records');
  assert.deepEqual(await place(null, [heldNow(XA, XA, 2)]), { error: 'UNAUTHENTICATED' });
  const today = new Date().toISOString().slice(0, 10);
  await seed('limits/al', { day: today, places: 99 });
  assert.deepEqual(await place('al', [heldNow(XA, XA, 3), heldNow(BP, Y, 4)]), { error: 'RESOURCE_EXHAUSTED' },
    'two records past 99 of 100');
  assert.deepEqual(await heldIn(), [], 'nothing written');
  assert.equal((await place('al', [heldNow(XA, XA, 5)])).result.shared.length, 1, 'the hundredth');
  assert.equal(Number((await read('limits/al')).places.integerValue), 100);
  assert.deepEqual(await place('al', [heldNow(XA, Y, 6)]), { error: 'RESOURCE_EXHAUSTED' }, 'the hundred and first');
});

test('D-230 acceptInvite: the join writes the held place marked when the record is right, and joins anyway when it is not', async () => {
  // A1: the join takes `records` (the new place's and the removed places the joiner held); one record here.
  // The D-210 fixture's group G: bo's place is free; the inviter adds three more.
  const FY = '1e'.repeat(16), GS = '2e'.repeat(16), HL = '3e'.repeat(16), IV = '4e'.repeat(16);
  const PF = '6a'.repeat(16), PG = '6b'.repeat(16);
  await op('ad', 11, { op: 'addMember', group: G, member: { id: FY, name: 'Fay' } });
  await op('ad', 12, { op: 'addMember', group: G, member: { id: GS, name: 'Gus' } });
  await op('ad', 13, { op: 'addMember', group: G, member: { id: HL, name: 'Hal' } });
  await op('ad', 14, { op: 'addMember', group: G, member: { id: IV, name: 'Ivy' } });
  const joinWith = (uid, token, ...records) => call('acceptInvite', uid, records.length ? { token, records } : { token });
  const answer = (memberId) => ({ groupId: G, memberId, groupName: 'Goa trip' });
  // Decision 57: the peek names the group and the place, so the phone can build the record before it joins.
  const token = await invite(M.bo, 'K'.repeat(43));
  const peeked = await call('peekInvite', 'eve', { token });
  assert.equal(peeked.result.groupId, G);
  assert.equal(peeked.result.memberId, M.bo);
  assert.deepEqual(await call('peekInvite', null, { token }), { error: 'UNAUTHENTICATED' });
  // eve's own participant id, in its own log: joined and marked in the same transaction.
  await youIn('eve', 0, P.eve);
  const r = heldNowIn(G, M.bo, P.eve, 1);
  assert.deepEqual((await joinWith('eve', token, r)).result, answer(M.bo));
  const [doc] = await list(`groups/${G}/records`).then((ds) => ds.filter((d) => d.fields.held?.booleanValue === true).map((d) => d.fields));
  assert.equal(doc.held.booleanValue, true);
  assert.equal(doc.authorUid.stringValue, 'eve');
  assert.equal(doc.removal, undefined);
  assert.deepEqual((await heldIn(G)).map((h) => [h.member, h.you, h.author]), [[M.bo, P.eve, 'eve']]);
  // A1: no record naming the invite's place is malformed: refused whole, the invite unspent. Beside the right one, a
  // record for another place that is not a removed place fay held: the join happens, fay's place marked, that one not.
  const token2 = await invite(FY, 'L'.repeat(43));
  await seed('users/fay', { participantId: PF, name: 'Fay' });
  await youIn('fay', 0, PF);
  const wrong = heldNowIn(G, M.bo, PF, 2);
  assert.deepEqual(await joinWith('fay', token2, wrong), { error: 'INVALID_ARGUMENT' }, 'no record names the invite\'s place');
  assert.ok(!strings((await read(`groups/${G}`)).memberUids).includes('fay'), 'not joined');
  const fays = heldNowIn(G, FY, PF, 7);
  assert.deepEqual((await joinWith('fay', token2, wrong, fays)).result, answer(FY));
  assert.deepEqual((await heldIn(G)).map((h) => [h.member, h.author]), [[M.bo, 'eve'], [FY, 'fay']], 'fay\'s place, not the one fay never held');
  // An id another account's mark names (eve's, on a present member): decision 66 refuses the whole join, unspent.
  // An id that is another account's participant id, unmarked: joined, not marked.
  const token3 = await invite(GS, 'N'.repeat(43));
  await seed('users/gus', { participantId: PG, name: 'Gus' });
  const stolen = heldNowIn(G, GS, P.eve, 3);
  assert.deepEqual(await joinWith('gus', token3, stolen), { error: 'FAILED_PRECONDITION' }, 'eve\'s marked id');
  assert.ok(!strings((await read(`groups/${G}`)).memberUids).includes('gus'), 'not joined');
  const sams = heldNowIn(G, GS, P.sam, 9);
  assert.deepEqual((await joinWith('gus', token3, sams)).result, answer(GS), 'sam\'s participant id, unmarked');
  const tokenH = await invite(HL, 'P'.repeat(43));
  assert.deepEqual(await joinWith('hal', tokenH, { nonsense: true }), { error: 'INVALID_ARGUMENT' }, 'a malformed record');
  assert.deepEqual(await call('acceptInvite', 'hal', { token: tokenH, record: heldNowIn(G, HL, P.eve, 8) }), { error: 'INVALID_ARGUMENT' },
    'the singular `record` is not accepted');
  assert.deepEqual((await joinWith('hal', tokenH)).result, answer(HL), 'no records: joined as an old build joins');
  // A raw place id as "you" (only a creator's place is its own id): joined, not marked.
  await youIn('ivy', 0, IV);
  const raw = heldNowIn(G, IV, IV, 4);
  assert.deepEqual((await joinWith('ivy', await invite(IV, 'Q'.repeat(43)), raw)).result, answer(IV));
  // An id another member's own log holds too (me copied it): joined, not marked (a copy is a denial).
  const JO = '5e'.repeat(16), PJ = '6d'.repeat(16);
  await op('ad', 15, { op: 'addMember', group: G, member: { id: JO, name: 'Jo' } });
  await seed('users/jo', { participantId: PJ, name: 'Jo' });
  await youIn('jo', 0, PJ);
  await youIn('me', 0, PJ);
  const copied = heldNowIn(G, JO, PJ, 5);
  assert.deepEqual((await joinWith('jo', await invite(JO, 'S'.repeat(43)), copied)).result, answer(JO));
  // A10: the phone's record id holding another document no longer matters (the server stamps the mark's id):
  // joined, marked under the server's id, that document untouched.
  const KT = '7d'.repeat(16), PK = '6e'.repeat(16);
  await op('ad', 16, { op: 'addMember', group: G, member: { id: KT, name: 'Kit' } });
  await seed('users/kit', { participantId: PK, name: 'Kit' });
  const taken = heldNowIn(G, KT, PK, 6);
  await seed(`groups/${G}/records/${taken.id}`, {
    type: '1', hlc: taken.hlc, authorUid: 'ow', deviceId: D, createdAt: new Date(),
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes({ group: G, payerId: M.ow, amountMinor: 5 }) },
  });
  assert.deepEqual((await joinWith('kit', await invite(KT, 'T'.repeat(43)), taken)).result, answer(KT));
  const kept = await read(`groups/${G}/records/${taken.id}`);
  assert.equal(kept.authorUid.stringValue, 'ow');
  assert.equal(kept.held, undefined);
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam', 'eve', 'fay', 'gus', 'hal', 'ivy', 'jo', 'kit']);
  assert.deepEqual((await heldIn(G)).map((h) => h.author), ['eve', 'fay', 'kit'], 'eve\'s, fay\'s and kit\'s; never gus\', ivy\'s or jo\'s');
});

// ---- D-230 fix wave: A3 (no raw place as `you`), A1 (a removed place its holder held, the rejoin's records), A5 ----

/** al's group with a placeholder Q (a place nobody holds) and a new place K the inviter adds; an unused invite to K. */
const QP = '1f'.repeat(16);
const KP = '2f'.repeat(16);
async function withPlaceholderAndK(token = 'U'.repeat(43)) {
  await opIn(GA, 'al', 5, { op: 'addMember', group: GA, member: { id: QP, name: 'Placeholder' } });
  await opIn(GA, 'al', 6, { op: 'addMember', group: GA, member: { id: KP, name: 'Bo again' } });
  return inviteIn(GA, KP, token);
}
/** al (the owner) removes `who` from its place in GA through the real Function. */
const removeIn = (who, member, you, n) => call('removeMember', 'al', {
  groupId: GA, member, you, memberName: who, groupName: 'Al trip', byName: 'Al',
  records: [rec('3', { op: 'removeMember', group: GA, member }, n)],
});
const joinGA = (uid, token, ...records) => call('acceptInvite', uid, records.length ? { token, records } : { token });

test('D-230 A3 sharePlaces: an id copied from a placeholder never marks your place (the takeover of the placeholder\'s history the check prevents)', async () => {
  await alGroup();
  await withPlaceholderAndK();
  await youIn('bo', 1, QP); // bo's modified phone copies the placeholder's id into its own log
  const viaQ = heldNow(BP, QP, 1);
  assert.deepEqual((await place('bo', [viaQ])).result, { shared: [], skipped: [viaQ.id] }, 'a placeholder\'s id');
  await youIn('bo', 2, CP); // and another member's raw place id
  const viaC = heldNow(BP, CP, 2);
  assert.deepEqual((await place('bo', [viaC])).result, { shared: [], skipped: [viaC.id] }, 'cy\'s place id');
  assert.deepEqual(await heldIn(), [], 'nothing written');
  const own = heldNow(BP, PID.bo, 3);
  assert.deepEqual((await place('bo', [own])).result, { shared: [own.id], skipped: [] }, 'bo\'s own id still marks it');
});

test('D-230 A3 acceptInvite: a joiner\'s id copied from a placeholder never marks the place it joins (the takeover the check prevents); the join happens', async () => {
  await alGroup();
  const token = await withPlaceholderAndK();
  const E8 = 'e8'.repeat(16);
  await seed('users/eve', { participantId: E8, name: 'Eve' });
  await youIn('eve', 0, E8);
  await youIn('eve', 1, QP);
  const viaQ = heldNow(KP, QP, 1);
  assert.deepEqual((await joinGA('eve', token, viaQ)).result, { groupId: GA, memberId: KP, groupName: 'Cy trip' });
  assert.ok(strings((await read(`groups/${GA}`)).memberUids).includes('eve'), 'joined');
  assert.deepEqual(await heldIn(), [], 'nothing marked');
});

test('D-230 A1 sharePlaces: a removed member who rejoined re-marks its old place; while removed it cannot; nobody else can', async () => {
  await alGroup();
  const token = await withPlaceholderAndK();
  assert.deepEqual((await removeIn('bo', BP, PID.bo, 1)).result, { removed: true });
  await inviteIn(GA, KP, token); // D-235 H1: the link to K is made after the removal (an older one no longer rejoins)
  assert.ok((await read(`invites/joined-bo-${BP.slice(0, 4)}`)).endedAt.timestampValue, 'bo\'s invite for BP ended');
  const early = heldNow(BP, PID.bo, 1);
  assert.deepEqual((await place('bo', [early])).result, { shared: [], skipped: [early.id] }, 'removed and not a member: skipped');
  assert.deepEqual((await joinGA('bo', token)).result, { groupId: GA, memberId: KP, groupName: 'Cy trip' }, 'bo rejoins at K');
  // Members who never held BP: cy (an invitee), al (the creator, whose place it is not).
  const cys = heldNow(BP, PID.cy, 2);
  assert.deepEqual((await place('cy', [cys])).result, { shared: [], skipped: [cys.id] }, 'cy never held it');
  const als = heldNow(BP, Y, 3);
  assert.deepEqual((await place('al', [als], SECOND_PHONE)).result, { shared: [], skipped: [als.id] }, 'al never held it');
  const old = heldNow(BP, PID.bo, 4);
  assert.deepEqual((await place('bo', [old])).result, { shared: [old.id], skipped: [] }, 'bo held it before');
  assert.deepEqual(await heldIn(), [{ member: BP, you: PID.bo, author: 'bo' }]);
});

test('D-230 decision 64: a removed place never changes hands: an older link to it is refused, so its old holder\'s re-mark stands alone', async () => {
  await alGroup();
  const token = await withPlaceholderAndK();
  assert.deepEqual((await removeIn('bo', BP, PID.bo, 1)).result, { removed: true });
  await inviteIn(GA, KP, token); // D-235 H1: the link to K is made after the removal (an older one no longer rejoins)
  // A link to BP that survived the removal (seeded: removeMember deletes the unused ones; one made in a race).
  const older = await inviteIn(GA, BP, 'V'.repeat(43));
  assert.ok((await joinGA('bo', token)).result, 'bo rejoins at K');
  const E8 = 'e8'.repeat(16);
  await seed('users/eve', { participantId: E8, name: 'Eve' });
  const tried = await accept('eve', older);
  assert.deepEqual(tried, { status: 'FAILED_PRECONDITION', message: 'That changed on another phone. Try again.', details: null });
  assert.ok(!strings((await read(`groups/${GA}`)).memberUids).includes('eve'), 'eve is not in');
  assert.deepEqual(await call('createInvite', 'al', { groupId: GA, memberId: BP, memberName: 'Bo', groupName: 'Al trip', inviterName: 'Al' }),
    { error: 'FAILED_PRECONDITION' }, 'nor is a new link made');
  const old = heldNow(BP, PID.bo, 1);
  assert.deepEqual((await place('bo', [old])).result, { shared: [old.id], skipped: [] }, 'BP stays bo\'s to re-mark');
});

test('D-230 A1 sharePlaces: a creator removed from its creator place (after a hand-over) re-marks it once back in', async () => {
  // The D-210 fixture: ow made G as M.ow (= P.ow, its participant id and "you").
  assert.ok((await call('makeOwner', 'ow', roles({ you: P.me }))).result);
  assert.deepEqual((await call('removeMember', 'me', remove('ow', { you: null }))).result, { removed: true });
  const K2 = '3f'.repeat(16);
  await op('ad', 11, { op: 'addMember', group: G, member: { id: K2, name: 'Ola again' } });
  const early = heldNowIn(G, M.ow, P.ow, 1);
  assert.deepEqual((await place('ow', [early])).result, { shared: [], skipped: [early.id] }, 'removed and not a member');
  assert.ok((await call('acceptInvite', 'ow', { token: await invite(K2, 'W'.repeat(43)) })).result, 'ow rejoins at K2');
  const mes = heldNowIn(G, M.ow, P.me, 2);
  assert.deepEqual((await place('me', [mes])).result, { shared: [], skipped: [mes.id] }, 'the new owner never held it');
  const old = heldNowIn(G, M.ow, P.ow, 3);
  assert.deepEqual((await place('ow', [old])).result, { shared: [old.id], skipped: [] }, 'its raw place, as its creator');
  // A6: its new place with the "you" it made the group under (its own former creator place's id).
  const now = heldNowIn(G, K2, P.ow, 4);
  assert.deepEqual((await place('ow', [now])).result, { shared: [now.id], skipped: [] }, 'K2 with you M.ow');
  assert.deepEqual((await heldIn(G)).map((h) => [h.member, h.you, h.author]), [[M.ow, P.ow, 'ow'], [K2, P.ow, 'ow']]);
});

test('D-230 A6 acceptInvite: a creator handed over, removed and re-invited marks its old place and its new one with its old place\'s id', async () => {
  assert.ok((await call('makeOwner', 'ow', roles({ you: P.me }))).result);
  assert.deepEqual((await call('removeMember', 'me', remove('ow', { you: null }))).result, { removed: true });
  const K2 = '3f'.repeat(16);
  await op('ad', 11, { op: 'addMember', group: G, member: { id: K2, name: 'Ola again' } });
  const old = heldNowIn(G, M.ow, P.ow, 1);
  const now = heldNowIn(G, K2, P.ow, 2);
  assert.deepEqual((await call('acceptInvite', 'ow', { token: await invite(K2, 'W'.repeat(43)), records: [old, now] })).result,
    { groupId: G, memberId: K2, groupName: 'Goa trip' });
  assert.deepEqual(await heldSet(G), [[M.ow, P.ow, 'ow'], [K2, P.ow, 'ow']].sort());
});

test('D-230 A6: another member who copied a removed creator\'s place id never marks its own place with it', async () => {
  // cy made GC as B (its other phone's "you", in nobody's log and nobody's participant id); dee joined X by invite.
  await cyGroup(GC, B, ['cy', 'dee']);
  const PD = '7c'.repeat(16);
  await seed('users/dee', { participantId: PD, name: 'Dee' });
  await seed(`invites/joined-dee-${GC.slice(0, 4)}`, { groupId: GC, memberId: X, usedBy: 'dee', usedAt: new Date() });
  assert.deepEqual((await call('makeOwner', 'cy', { groupId: GC, groupName: 'Cy trip', member: X, you: PD })).result, { owner: PD });
  assert.deepEqual((await call('removeMember', 'dee', { groupId: GC, member: B, you: null, memberName: 'Cy', groupName: 'Cy trip',
    byName: 'Dee', records: [rec('3', { op: 'removeMember', group: GC, member: B }, 1)] })).result, { removed: true });
  await youIn('dee', 0, PD);
  await youIn('dee', 1, B); // dee's modified phone copies cy's old creator place id
  const viaB = heldNowIn(GC, X, B, 1);
  assert.deepEqual((await place('dee', [viaB])).result, { shared: [], skipped: [viaB.id] }, 'B is cy\'s former creator place, not dee\'s');
  const own = heldNowIn(GC, X, PD, 2);
  assert.deepEqual((await place('dee', [own])).result, { shared: [own.id], skipped: [] }, 'dee\'s own id still marks X');
  assert.deepEqual((await heldIn(GC)).map((h) => [h.member, h.you]), [[X, PD]]);
});

test('D-230 A1 sharePlaces: a creator place an invitee held (an ended invite of another account) is not re-marked by the creator', async () => {
  assert.ok((await call('makeOwner', 'ow', roles({ you: P.me }))).result);
  assert.deepEqual((await call('removeMember', 'me', remove('ow', { you: null }))).result, { removed: true });
  // Seeded: eve's accepted invite for M.ow (decision 36: it won over the creator), ended by a removal.
  await seed('invites/held-eve-ow', { groupId: G, memberId: M.ow, memberName: 'Ola', groupName: 'Goa trip', inviterName: 'Mo',
    createdBy: 'me', createdAt: new Date(), expiresAt: new Date(Date.now() + 3600000), usedBy: 'eve', usedAt: new Date(), endedAt: new Date() });
  const K2 = '3f'.repeat(16);
  await op('ad', 11, { op: 'addMember', group: G, member: { id: K2, name: 'Ola again' } });
  assert.ok((await call('acceptInvite', 'ow', { token: await invite(K2, 'W'.repeat(43)) })).result, 'ow rejoins at K2');
  const old = heldNowIn(G, M.ow, P.ow, 1);
  assert.deepEqual((await place('ow', [old])).result, { shared: [], skipped: [old.id] });
  assert.deepEqual(await heldIn(G), []);
});

test('D-230 A1 acceptInvite: a rejoin sends the old place and the new: both marked', async () => {
  await alGroup();
  const token = await withPlaceholderAndK();
  assert.deepEqual((await removeIn('bo', BP, PID.bo, 1)).result, { removed: true });
  await inviteIn(GA, KP, token); // D-235 H1: the link to K is made after the removal (an older one no longer rejoins)
  const old = heldNow(BP, PID.bo, 1);
  const now = heldNow(KP, PID.bo, 2);
  assert.deepEqual((await joinGA('bo', token, old, now)).result, { groupId: GA, memberId: KP, groupName: 'Cy trip' });
  assert.deepEqual(await heldSet(), [[BP, PID.bo, 'bo'], [KP, PID.bo, 'bo']].sort(), 'both marked (their order is the server\'s, A2 makes it moot)');
});

test('D-230 A1 acceptInvite: an old record for a place the joiner never held is skipped; the new one is written; the join succeeds', async () => {
  await alGroup();
  const token = await withPlaceholderAndK();
  assert.deepEqual((await removeIn('cy', CP, PID.cy, 1)).result, { removed: true });
  assert.deepEqual((await removeIn('bo', BP, PID.bo, 2)).result, { removed: true });
  await inviteIn(GA, KP, token); // D-235 H1: the link to K is made after the removal (an older one no longer rejoins)
  const notMine = heldNow(CP, PID.bo, 1); // cy's removed place
  const never = heldNow(QP, PID.bo, 2); // the placeholder, never removed, never bo's
  const now = heldNow(KP, PID.bo, 3);
  assert.deepEqual((await joinGA('bo', token, notMine, never, now)).result, { groupId: GA, memberId: KP, groupName: 'Cy trip' });
  assert.deepEqual(await heldIn(), [{ member: KP, you: PID.bo, author: 'bo' }]);
});

test('D-230 A1/A5 acceptInvite: twelve records, a duplicate triple, none for the invite\'s place, another group: refused whole, the invite unspent', async () => {
  await alGroup();
  const token = await withPlaceholderAndK();
  assert.deepEqual((await removeIn('bo', BP, PID.bo, 1)).result, { removed: true });
  await inviteIn(GA, KP, token); // D-235 H1: the link to K is made after the removal (an older one no longer rejoins)
  const hash = createHash('sha256').update(token).digest('hex');
  const now = heldNow(KP, PID.bo, 100);
  const olds = Array.from({ length: 11 }, (_, i) => heldNow(`${(i + 16).toString(16)}`.repeat(16), PID.bo, i + 1));
  const bad = [
    [[...olds, now], 'twelve: the phone caps at eleven'],
    [[heldNow(BP, PID.bo, 1), heldNow(BP, PID.bo, 2), now], 'one (group, member, you) twice'],
    [[heldNow(BP, PID.bo, 3)], 'no record for the invite\'s place'],
    [[heldNow(BP, PID.bo, 1, G), now], 'a record of another group'],
  ];
  for (const [records, why] of bad) {
    assert.deepEqual(await joinGA('bo', token, ...records), { error: 'INVALID_ARGUMENT' }, why);
    assert.equal((await read(`invites/${hash}`)).usedBy.nullValue, null, `${why}: the invite unspent`);
  }
  assert.deepEqual(await heldIn(), [], 'nothing written');
  assert.ok(!strings((await read(`groups/${GA}`)).memberUids).includes('bo'), 'not joined');
  // Eleven (ten old, the new): accepted; the old ones name no place bo held and are skipped.
  assert.deepEqual((await joinGA('bo', token, ...olds.slice(0, 10), now)).result, { groupId: GA, memberId: KP, groupName: 'Cy trip' });
  assert.deepEqual((await heldIn()).map((h) => h.member), [KP]);
});

// ---- D-230 round 2 (A8): removed places are never invited again (decision 64), at least one record for the
// invite's place, creatorBefore's own place condition, both directions of the clock, records never pass a refused join ----

test('D-230 decision 64: createInvite, inviteUser, inviteKnown and acceptInvite refuse a removed place; added back, still refused', async () => {
  await seed('users/out', { name: 'Out', findable: true, participantId: '8'.repeat(32) });
  const V = 'e'.repeat(32);
  const PK = '7'.repeat(32);
  await seed(`groups/${V}`, { name: 'Flat', memberUids: ['me', 'kit'], createdBy: 'me', createdAt: new Date() });
  await seed('users/kit', { name: 'Kit', findable: false, participantId: PK });
  assert.deepEqual((await call('removeMember', 'ad', remove('sam'))).result, { removed: true });
  // A link to the place that survived the removal (seeded: removeMember deletes the unused ones; one made in a race).
  const older = await invite(M.sam, 'X'.repeat(43));
  const invitesBefore = await snapshotOf('invites');
  const limitsBefore = await read('limits/me');
  const CH = { error: 'FAILED_PRECONDITION' };
  assert.deepEqual(await call('createInvite', 'me', link(M.sam)), CH, 'createInvite');
  assert.deepEqual(await call('inviteUser', 'me', { ...link(M.sam), targetUid: 'out' }), CH, 'inviteUser');
  assert.deepEqual(await call('inviteKnown', 'me', { ...link(M.sam), viaGroupId: V, participantId: PK }), CH, 'inviteKnown');
  assert.deepEqual(await snapshotOf('invites'), invitesBefore, 'no link invite was written');
  assert.equal((await list('users/out/invites')).length, 0, 'inviteUser delivered nothing');
  assert.equal((await list('users/kit/invites')).length, 0, 'inviteKnown delivered nothing');
  assert.deepEqual(await read('limits/me'), limitsBefore, 'no daily quota was spent');
  const hash = createHash('sha256').update(older).digest('hex');
  assert.deepEqual(await accept('eve', older), { status: 'FAILED_PRECONDITION', message: 'That changed on another phone. Try again.', details: null },
    'acceptInvite: an older link');
  assert.equal((await read(`invites/${hash}`)).usedBy.nullValue, null, 'the invite unspent');
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me'], 'nobody joined');
  // A9: added back by a phone, it is still never invited again (everRemoved).
  await op('ad', 12, { op: 'addMember', group: G, member: { id: M.sam, name: 'Sam' } });
  assert.deepEqual(await call('createInvite', 'me', link(M.sam)), CH, 'added back: still refused');
  assert.deepEqual((await accept('eve', older)).status, 'FAILED_PRECONDITION', 'added back: the older link still refused');
  assert.equal((await read(`invites/${hash}`)).usedBy.nullValue, null, 'still unspent');
});

test('D-230 A8 I2 acceptInvite: two records for the invite\'s place under two of the account\'s ids are both written', async () => {
  await alGroup();
  const token = await withPlaceholderAndK();
  const E8 = 'e8'.repeat(16);
  const E9 = 'e9'.repeat(16);
  await seed('users/eve', { participantId: E8, name: 'Eve' });
  await youIn('eve', 0, E9); // eve's other phone's "you"
  const a = heldNow(KP, E8, 1);
  const b = heldNow(KP, E9, 2);
  assert.deepEqual((await joinGA('eve', token, a, b)).result, { groupId: GA, memberId: KP, groupName: 'Cy trip' });
  assert.deepEqual(await heldSet(), [[KP, E8, 'eve'], [KP, E9, 'eve']].sort());
});

/** Orders two stamped HLCs as core's Hlc does: millis, then counter, then the device. */
const hlcCmp = (x, y) => (x.millis - y.millis) || (x.counter - y.counter) || (x.device < y.device ? -1 : x.device > y.device ? 1 : 0);
/** The `you` of the latest mark per member, by the server's stamp (core takes the latest). */
const latestYou = async (member, group = GA) => (await heldDocs(group)).filter((h) => h.member === member).sort((x, y) => hlcCmp(x.hlc, y.hlc)).at(-1)?.you;

test('D-230 round 6: within one call the record\'s index is the stamp\'s counter, so the last in the list is the latest', async () => {
  // sharePlaces: al's two ids for its creator place in one call.
  await alGroup();
  const first = heldNow(XA, XA, 1);
  const last = heldNow(XA, Y, 2);
  assert.deepEqual((await place('al', [first, last], SECOND_PHONE)).result, { shared: [first.id, last.id], skipped: [] });
  const byYou = Object.fromEntries((await heldDocs()).map((h) => [h.you, h.hlc.counter]));
  assert.deepEqual(byYou, { [XA]: 0, [Y]: 1 }, 'sharePlaces: the counter is the record\'s index');
  assert.equal(await latestYou(XA), Y, 'sharePlaces: the last in the list is the latest');
  for (const h of await heldDocs()) assert.equal(h.id, `${h.hlc.millis}-${h.hlc.counter}-${h.hlc.device}`, 'the id follows the stamp');
});

test('D-230 round 6: acceptInvite stamps the same way (the join\'s two ids for the invite\'s place)', async () => {
  await alGroup();
  const token = await withPlaceholderAndK();
  const E8 = 'e8'.repeat(16);
  const E9 = 'e9'.repeat(16);
  await seed('users/eve', { participantId: E8, name: 'Eve' });
  await youIn('eve', 0, E9);
  const a = heldNow(KP, E8, 1);
  const b = heldNow(KP, E9, 2);
  assert.ok((await joinGA('eve', token, a, b)).result);
  const byYou = Object.fromEntries((await heldDocs()).map((h) => [h.you, h.hlc.counter]));
  assert.deepEqual(byYou, { [E8]: 0, [E9]: 1 }, 'acceptInvite: the counter is the record\'s index');
  assert.equal(await latestYou(KP), E9, 'acceptInvite: the last in the list is the latest');
  for (const h of await heldDocs()) assert.equal(h.id, `${h.hlc.millis}-${h.hlc.counter}-${h.hlc.device}`, 'the id follows the stamp');
});

test('D-230 A8 I3: the creator never re-marks a removed placeholder it never held, nor marks with its id', async () => {
  await alGroup();
  await withPlaceholderAndK();
  // al (the owner) removes the placeholder QP; nobody ever joined it by invite.
  assert.deepEqual((await removeIn('Placeholder', QP, null, 1)).result, { removed: true });
  const viaY = heldNow(QP, Y, 1);
  assert.deepEqual((await place('al', [viaY], SECOND_PHONE)).result, { shared: [], skipped: [viaY.id] }, '{QP, you Y}: not al\'s creator place');
  await youIn('al', 5, QP); // al's modified phone copies the placeholder's id
  const viaQ = heldNow(XA, QP, 2);
  assert.deepEqual((await place('al', [viaQ])).result, { shared: [], skipped: [viaQ.id] }, '{XA, you QP}: QP is no former creator place');
  assert.deepEqual(await heldIn(), []);
});

test('D-230 A10 decision 54: the server stamps a mark\'s identity; the phone\'s clock and record id play no part; the reply echoes the phone\'s ids', async () => {
  await alGroup();
  const token = await withPlaceholderAndK();
  // Phone stamps eleven minutes ahead and in the fixture's far past (2 s after the epoch): both irrelevant now.
  const ahead = heldNow(XA, XA, 11 * 60 * 1000);
  const past = heldOf(XA, Y, 2);
  const before = Date.now();
  assert.deepEqual((await place('al', [ahead, past], SECOND_PHONE)).result, { shared: [ahead.id, past.id], skipped: [] },
    'the reply names the phone\'s record ids');
  const E8 = 'e8'.repeat(16);
  await seed('users/eve', { participantId: E8, name: 'Eve' });
  const joining = heldNow(KP, E8, 11 * 60 * 1000);
  assert.deepEqual((await joinGA('eve', token, joining)).result, { groupId: GA, memberId: KP, groupName: 'Cy trip' });
  const after = Date.now();
  const docs = await heldDocs();
  assert.equal(docs.length, 3);
  for (const d of docs) {
    assert.match(d.id, /^\d+-[01]-[0-9a-f]{32}$/, 'an id of the record shape');
    assert.equal(d.id, `${d.hlc.millis}-${d.hlc.counter}-${d.hlc.device}`, 'the id follows the stamp');
    assert.ok(d.hlc.millis >= before - 60000 && d.hlc.millis <= after + 60000, `the server's clock: ${d.hlc.millis}`);
    // Round 6: the counter is the record's index in its call (past is second in sharePlaces' list).
    assert.equal(d.hlc.counter, d.you === Y ? 1 : 0);
    assert.notEqual(d.hlc.device, D, 'not the phone\'s device');
    assert.equal(d.deviceId, D, 'deviceId stays the phone\'s');
    assert.ok(![ahead.id, past.id, joining.id].includes(d.id), 'never the phone\'s id');
  }
  assert.equal(new Set(docs.map((d) => d.id)).size, 3, 'two records in one call get distinct ids');
  assert.deepEqual(docs.map((d) => [d.member, d.you, d.author]).sort(), [[XA, XA, 'al'], [XA, Y, 'al'], [KP, E8, 'eve']].sort());
});

test('D-230 A8 M6: a join refused as held, with records attached, writes nothing and spends nothing', async () => {
  const token = await invite(M.me, 'Y'.repeat(43)); // me's place, held by me
  const hash = createHash('sha256').update(token).digest('hex');
  await youIn('eve', 0, P.eve);
  const before = { group: await read(`groups/${G}`), records: await snapshotOf(`groups/${G}/records`) };
  assert.deepEqual(await accept('eve', token, [heldNowIn(G, M.me, P.eve, 1)]),
    { status: 'FAILED_PRECONDITION', message: 'Someone has already joined as this person.', details: null });
  assert.equal((await read(`invites/${hash}`)).usedBy.nullValue, null, 'the invite unspent');
  assert.deepEqual(await read(`groups/${G}`), before.group, 'membership untouched');
  assert.deepEqual(await snapshotOf(`groups/${G}/records`), before.records, 'no record written');
});

// ---- D-230 round 3 (A9): a place once removed is never invited again; a former holder's re-mark is its own alone ----

test('D-230 A9: a removed id a member adds back is still not invitable; the former holder alone re-marks it', async () => {
  await alGroup();
  const token = await withPlaceholderAndK();
  assert.deepEqual((await removeIn('bo', BP, PID.bo, 1)).result, { removed: true });
  await inviteIn(GA, KP, token); // D-235 H1: the link to K is made after the removal (an older one no longer rejoins)
  assert.ok((await joinGA('bo', token)).result, 'bo rejoins at K');
  const old = heldNow(BP, PID.bo, 1);
  assert.deepEqual((await place('bo', [old])).result, { shared: [old.id], skipped: [] }, 'bo re-marks BP');
  // cy's phone adds BP back: a placeholder nobody can join.
  await opIn(GA, 'cy', 7, { op: 'addMember', group: GA, member: { id: BP, name: 'Bo' } });
  assert.deepEqual(await call('createInvite', 'al', { groupId: GA, memberId: BP, memberName: 'Bo', groupName: 'Al trip', inviterName: 'Al' }),
    { error: 'FAILED_PRECONDITION' }, 'createInvite');
  const E8 = 'e8'.repeat(16);
  await seed('users/eve', { participantId: E8, name: 'Eve' });
  const link6 = await inviteIn(GA, BP, 'Z'.repeat(43)); // a link seeded past refuseHeld
  assert.deepEqual((await accept('eve', link6)).status, 'FAILED_PRECONDITION', 'acceptInvite');
  assert.equal((await read(`invites/${createHash('sha256').update(link6).digest('hex')}`)).usedBy.nullValue, null, 'unspent');
  assert.deepEqual((await heldIn()).map((h) => [h.member, h.you, h.author]), [[BP, PID.bo, 'bo']], 'only bo\'s mark');
});

test('D-230 A9: a former holder never re-marks a removed place another account ever joined (history from before decision 64)', async () => {
  await alGroup();
  const token = await withPlaceholderAndK();
  // Seeded: eve held BP before bo and was removed (her invite ended); no Function makes this any more.
  await seed('invites/held-eve-bp', { groupId: GA, memberId: BP, memberName: 'Bo', groupName: 'Al trip', inviterName: 'Al',
    createdBy: 'al', createdAt: new Date(), expiresAt: new Date(Date.now() + 3600000), usedBy: 'eve', usedAt: new Date(), endedAt: new Date() });
  assert.deepEqual((await removeIn('bo', BP, PID.bo, 1)).result, { removed: true });
  await inviteIn(GA, KP, token); // D-235 H1: the link to K is made after the removal (an older one no longer rejoins)
  assert.ok((await joinGA('bo', token)).result, 'bo rejoins at K');
  const old = heldNow(BP, PID.bo, 1);
  assert.deepEqual((await place('bo', [old])).result, { shared: [], skipped: [old.id] }, 'another account held BP too');
  assert.deepEqual(await heldIn(), []);
});

// ---- D-230 round 4 (R3-M1): an add-back of a removed id never turns off its former holder's re-mark ----

test('D-230 R3-M1: bo\'s old place added back by a co-member: bo, back at a new place, still re-marks it', async () => {
  await alGroup();
  const token = await withPlaceholderAndK();
  assert.deepEqual((await removeIn('bo', BP, PID.bo, 1)).result, { removed: true });
  await inviteIn(GA, KP, token); // D-235 H1: the link to K is made after the removal (an older one no longer rejoins)
  await opIn(GA, 'cy', 7, { op: 'addMember', group: GA, member: { id: BP, name: 'Bo' } }); // cy's phone adds BP back
  assert.ok((await joinGA('bo', token)).result, 'bo rejoins at K');
  const old = heldNow(BP, PID.bo, 1);
  assert.deepEqual((await place('bo', [old])).result, { shared: [old.id], skipped: [] });
  assert.deepEqual((await heldIn()).map((h) => [h.member, h.you, h.author]), [[BP, PID.bo, 'bo']]);
});

test('D-230 R3-M1: a removed creator whose place was added back still marks it and its new place with its id (A6)', async () => {
  assert.ok((await call('makeOwner', 'ow', roles({ you: P.me }))).result);
  assert.deepEqual((await call('removeMember', 'me', remove('ow', { you: null }))).result, { removed: true });
  await op('ad', 12, { op: 'addMember', group: G, member: { id: M.ow, name: 'Ola' } }); // ad's phone adds M.ow back
  const K2 = '3f'.repeat(16);
  await op('ad', 11, { op: 'addMember', group: G, member: { id: K2, name: 'Ola again' } });
  const old = heldNowIn(G, M.ow, P.ow, 1);
  const now = heldNowIn(G, K2, P.ow, 2);
  assert.deepEqual((await call('acceptInvite', 'ow', { token: await invite(K2, 'W'.repeat(43)), records: [old, now] })).result,
    { groupId: G, memberId: K2, groupName: 'Goa trip' });
  assert.deepEqual(await heldSet(G), [[M.ow, P.ow, 'ow'], [K2, P.ow, 'ow']].sort());
});

// ---- D-230 round 5 (A10): decision 66, the first account to mark an id keeps it ----

const V2 = 'f2'.repeat(16); // eve's adopted "you" (her other phone's), not her participant id

test('D-230 decision 66: a copier marks the victim\'s adopted id first: the victim\'s join is refused whole; once the copier\'s member is removed the victim joins', async () => {
  await alGroup();
  const token = await withPlaceholderAndK();
  const E8 = 'e8'.repeat(16);
  await seed('users/eve', { participantId: E8, name: 'Eve' });
  await youIn('eve', 0, V2);
  await youIn('cy', 1, V2); // cy's modified phone copies eve's id before eve is in the group
  const cys = heldNow(CP, V2, 1);
  assert.deepEqual((await place('cy', [cys])).result, { shared: [cys.id], skipped: [] }, 'nobody in the group holds V2 yet');
  const hash = createHash('sha256').update(token).digest('hex');
  const refused = await accept('eve', token, [heldNow(KP, V2, 2), heldNow(KP, E8, 3)]);
  assert.deepEqual(refused, { status: 'FAILED_PRECONDITION', message: 'Someone has already joined as this person.', details: null });
  assert.equal((await read(`invites/${hash}`)).usedBy.nullValue, null, 'the invite unspent');
  assert.ok(!strings((await read(`groups/${GA}`)).memberUids).includes('eve'), 'not joined');
  assert.deepEqual(await heldIn(), [{ member: CP, you: V2, author: 'cy' }], 'nothing written');
  // The owner removes the copier's member: the refusal counts present members only.
  assert.deepEqual((await removeIn('cy', CP, PID.cy, 1)).result, { removed: true });
  await inviteIn(GA, KP, token); // D-235 H1: the link to K is made after the removal (an older one no longer rejoins)
  assert.deepEqual((await accept('eve', token, [heldNow(KP, V2, 4), heldNow(KP, E8, 5)])).result,
    { groupId: GA, memberId: KP, groupName: 'Cy trip' }, 'eve joins');
  // V2 stays denied to eve (decision 46: the former copier's log holds it); her participant id marks K.
  assert.deepEqual(await heldSet(), [[CP, V2, 'cy'], [KP, E8, 'eve']].sort());
});

test('D-230 decision 66: sharePlaces skips an id another account\'s mark names on a present member, and writes it once that member is removed', async () => {
  await alGroup();
  await youIn('cy', 1, V2);
  const cys = heldNow(CP, V2, 1);
  assert.deepEqual((await place('cy', [cys])).result, { shared: [cys.id], skipped: [] });
  // cy's account is deleted (account-store drops it from memberUids; no former entry): only the mark remembers V2.
  await seed(`groups/${GA}`, { name: 'Al trip', memberUids: ['al', 'bo'], createdBy: 'al', createdAt: new Date() });
  await youIn('bo', 1, V2); // bo's modified phone copies V2
  const bos = heldNow(BP, V2, 2);
  assert.deepEqual((await place('bo', [bos])).result, { shared: [], skipped: [bos.id] }, 'cy\'s mark names V2 on CP, present');
  assert.deepEqual((await removeIn('cy', CP, null, 1)).result, { removed: true });
  const again = heldNow(BP, V2, 3);
  assert.deepEqual((await place('bo', [again])).result, { shared: [again.id], skipped: [] }, 'CP removed: the mark no longer counts');
  assert.deepEqual(await heldSet(), [[BP, V2, 'bo'], [CP, V2, 'cy']].sort());
});

test('D-230 decision 66: the same account\'s own earlier mark never refuses it', async () => {
  await alGroup();
  await opIn(GA, 'al', 5, { op: 'addMember', group: GA, member: { id: X2, name: 'Al too' } });
  await joinedGA('al', X2);
  const first = heldNow(XA, Y, 1);
  assert.deepEqual((await place('al', [first], SECOND_PHONE)).result, { shared: [first.id], skipped: [] });
  const second = heldNow(X2, Y, 2);
  assert.deepEqual((await place('al', [second], SECOND_PHONE)).result, { shared: [second.id], skipped: [] }, 'al\'s own mark names Y');
  assert.deepEqual(await heldSet(), [[X2, Y, 'al'], [XA, Y, 'al']].sort());
});

// ---- D-235: the Opus pre-release audit's fixes (wave B) ----

/** Many documents at once, through :commit with the emulator's owner token,
 *  500 a request: [[path, fields]]. */
async function seedMany(docs) {
  for (let i = 0; i < docs.length; i += 500) {
    const writes = docs.slice(i, i + 500).map(([path, fields]) => ({
      update: { name: `${DOCS}/${path}`, fields: Object.fromEntries(Object.entries(fields).map(([k, v]) => [k, value(v)])) },
    }));
    const r = await fetch(`${FS}/v1/${DOCS}:commit`, {
      method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer owner' },
      body: JSON.stringify({ writes }),
    });
    assert.equal(r.status, 200);
  }
}
/** `n` small type-3 records under `parent` (a records collection), ids from `prefix`. */
const fillers = (parent, n, prefix, blob, author = 'ow', at = (i) => new Date(Date.now() + i)) => Array.from({ length: n }, (_, i) => [
  `${parent}/${prefix}${String(i).padStart(5, '0')}-0-${D}`,
  { type: '3', hlc: { millis: 5000 + i, counter: 0, device: D }, authorUid: author, deviceId: D, createdAt: at(i),
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes(typeof blob === 'function' ? blob(i) : blob) } },
]);

test('D-235 H1: a removed member never comes back through a link made before the removal; a later one still works', async () => {
  // me mints a link while a member (bo's place), and ad makes one for a new place K, both before me is removed.
  const mine = (await call('createInvite', 'me', link(M.bo))).result.token;
  const K = '6c'.repeat(16);
  await op('ad', 11, { op: 'addMember', group: G, member: { id: K, name: 'Mo' } });
  const older = 'O'.repeat(43);
  await seed(`invites/${createHash('sha256').update(older).digest('hex')}`, { groupId: G, memberId: K, memberName: 'Mo',
    groupName: 'Goa trip', inviterName: 'Asha', createdBy: 'ad', createdAt: new Date(), expiresAt: new Date(Date.now() + 3600000), usedBy: null });
  await new Promise((r) => setTimeout(r, 5));
  assert.deepEqual((await call('removeMember', 'ad', remove('me'))).result, { removed: true });
  // me's own unused links in the group are void with the removal (hygiene).
  assert.deepEqual(await call('acceptInvite', 'me', { token: mine }), { error: 'NOT_FOUND' }, 'the link me made is gone');
  // An older link made by a member before the removal, forwarded to me: refused, unspent, me still former.
  const olderHash = createHash('sha256').update(older).digest('hex');
  assert.deepEqual(await call('acceptInvite', 'me', { token: older }), { error: 'FAILED_PRECONDITION' }, 'made before the removal');
  assert.equal((await read(`invites/${olderHash}`)).usedBy.nullValue, null, 'unspent');
  const g = await read(`groups/${G}`);
  assert.deepEqual(strings(g.memberUids), ['ow', 'ad', 'sam']);
  assert.ok(g.former.mapValue.fields.me, 'still former');
  // A manager's link made after the removal brings me back (decision 49).
  const fresh = (await call('createInvite', 'ad', link(K))).result.token;
  assert.ok((await call('acceptInvite', 'me', { token: fresh })).result, 'a link made after the removal');
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'sam', 'me']);
});

test('D-235 H1: a link for a place the group\'s log never made is refused at the join, unspent', async () => {
  const R = '5e'.repeat(16); // no create or addMember names it
  const token = (await call('createInvite', 'me', link(R))).result.token;
  const hash = createHash('sha256').update(token).digest('hex');
  assert.deepEqual(await call('acceptInvite', 'eve', { token }), { error: 'FAILED_PRECONDITION' });
  assert.equal((await read(`invites/${hash}`)).usedBy.nullValue, null, 'unspent');
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me', 'sam'], 'eve is not in');
  // Once the place reaches the log, the same link works.
  await op('me', 12, { op: 'addMember', group: G, member: { id: R, name: 'Eve' } });
  assert.ok((await call('acceptInvite', 'eve', { token })).result);
});

test('D-235 L2: removeMember voids the place\'s unused links with their in-app copies on other accounts', async () => {
  await seed('users/out', { name: 'Out', participantId: '8'.repeat(32) });
  assert.deepEqual((await call('inviteUser', 'ad', { ...link(M.bo), targetUid: 'out' })).result, { invited: true });
  assert.equal((await list('users/out/invites')).length, 1, 'delivered');
  await seed('users/out/invites/other', { groupId: G, memberId: M.bo, token: 'Z'.repeat(43), groupName: 'Goa trip' });
  assert.ok((await call('removeMember', 'ad', remove('bo', { you: null, records: [removalOf(M.bo)] }))).result);
  assert.deepEqual((await list('users/out/invites')).map(idOf), ['other'], 'the copy of the voided link is gone; nothing else');
  assert.equal((await list('invites')).filter((d) => d.fields.memberId?.stringValue === M.bo && !d.fields.usedBy?.stringValue).length, 0);
});

test('D-235 M2: notices name the group as its log does, never as the phone says', async () => {
  await op('ad', 12, { op: 'renameGroup', group: G, name: 'Goa 2026' });
  const lie = 'Your account is locked: skipti-help.example';
  assert.ok((await call('setAdmin', 'ad', roles({ you: P.me, on: true, groupName: lie }))).result);
  assert.equal((await notices('me'))[0].groupName.stringValue, 'Goa 2026', 'setAdmin');
  assert.ok((await call('requestRemoval', 'me', ask({ groupName: lie }))).result);
  for (const uid of ['ow', 'ad', 'sam']) {
    const n = (await notices(uid)).find((x) => x.kind.stringValue === 'request');
    assert.equal(n.groupName.stringValue, 'Goa 2026', `requestRemoval to ${uid}`);
  }
  assert.ok((await call('removeMember', 'ow', remove('sam', { groupName: lie }))).result);
  assert.equal((await notices('sam')).find((x) => x.kind.stringValue === 'removed').groupName.stringValue, 'Goa 2026', 'removeMember');
  assert.ok((await call('deleteGroup', 'ow', { groupId: G, groupName: lie })).result);
  assert.equal((await notices('ad')).find((x) => x.kind.stringValue === 'deleted').groupName.stringValue, 'Goa 2026', 'deleteGroup');
});

// ---- D-235 round 2 (C1): the log is read per author, oldest first, never refused ----

const renames = (author, n, prefix = '2', at) =>
  fillers(`groups/${G}/records`, n, prefix, { op: 'renameMember', group: G, member: M.bo, name: 'Bo' }, author, at);
/** An op `uid` wrote at server time `at`, under a record id of our choice. */
async function opAt(uid, id, blob, at) {
  await seed(`groups/${G}/records/${id}`, {
    type: '3', hlc: { millis: at.getTime(), counter: 0, device: D }, authorUid: uid, deviceId: D, createdAt: at,
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes(blob) },
  });
}

test('D-235 round 2 C1: a member who floods the log (1001 ops) is still removable; roles, invites and joins still work', async () => {
  await seedMany(renames('me', 1001));
  assert.deepEqual(roleIds(await call('groupRoles', 'sam', { groupId: G })), { owner: P.ow, admins: [P.ad] }, 'groupRoles answers');
  const token = (await call('createInvite', 'ad', link(M.bo))).result.token;
  assert.ok((await call('acceptInvite', 'eve', { token })).result, 'eve joins bo\'s place');
  assert.ok((await call('setAdmin', 'ow', roles({ you: P.sam, on: true }))).result, 'roles change');
  assert.deepEqual((await call('removeMember', 'ad', remove('me'))).result, { removed: true }, 'the flooder is removed');
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'sam', 'eve']);
});

test('D-235 round 2 C1: past its bound an author\'s later ops are not read, but a removal it writes always counts', async () => {
  const Z = '5a'.repeat(16);
  const t = Date.now() - 3600000;
  await seedMany(renames('ad', 300, '2', (i) => new Date(t + i)));
  await opAt('ad', `9000-0-${D}`, { op: 'addMember', group: G, member: { id: Z, name: 'Zed' } }, new Date(t + 1000)); // ad's 301st
  const token = (await call('createInvite', 'me', link(Z))).result.token;
  assert.deepEqual(await call('acceptInvite', 'eve', { token }), { error: 'FAILED_PRECONDITION' }, 'Z: ad\'s op past the bound');
  assert.deepEqual((await call('removeMember', 'ad', remove('sam'))).result, { removed: true });
  assert.deepEqual(await call('createInvite', 'me', link(M.sam)), { error: 'FAILED_PRECONDITION' }, 'sam\'s removal is read (decision 64)');
  assert.deepEqual(await call('requestRemoval', 'me', ask({ you: null })), { error: 'FAILED_PRECONDITION' }, 'removed already');
});

test('D-235 round 2 C1: an author is read oldest first by the server\'s time, so a creator\'s flood never hides its create', async () => {
  assert.ok((await call('makeOwner', 'ow', roles({ you: P.me }))).result, 'ow hands the group to me');
  // 301 ops whose ids sort before the create op's (`1000-0-…`): read by id, the create would be cut.
  await seedMany(renames('ow', 301, '0'));
  assert.deepEqual((await call('removeMember', 'me', remove('ow', { you: null }))).result, { removed: true });
  assert.ok(!strings((await read(`groups/${G}`)).memberUids).includes('ow'));
});

test('D-235 round 2 C1: an author\'s compact ops are bounded in size too', async () => {
  const Z = '5b'.repeat(16);
  const t = Date.now() - 3600000;
  const many = Array.from({ length: 600 }, (_, i) => ({ id: i.toString(16).padStart(32, '0'), name: 'X' }));
  // Nine create ops naming 600 places each, read as 500 (MEMBERS_MAX), about 60,300 bytes each (groups.opBytes): eight
  // fit in 512 KiB; the ninth, which names Z first, would pass it (round 3: checked BEFORE keeping) and is not kept, nor
  // anything of me's after it.
  await seedMany(fillers(`groups/${G}/records`, 9, '2', (i) => ({ op: 'create', group: G, settlementCurrency: 'INR',
    members: i === 8 ? [{ id: Z, name: 'Zed' }, ...many] : many }), 'me', (i) => new Date(t + i)));
  const Z2 = '5f'.repeat(16);
  await opAt('me', `9000-0-${D}`, { op: 'addMember', group: G, member: { id: Z2, name: 'Zed 2' } }, new Date(t + 1000));
  const token = (await call('createInvite', 'ad', link(Z))).result.token;
  assert.deepEqual(await call('acceptInvite', 'eve', { token }), { error: 'FAILED_PRECONDITION' }, 'the op that would pass the budget');
  const token2 = (await call('createInvite', 'ad', link(Z2))).result.token;
  assert.deepEqual(await call('acceptInvite', 'eve', { token: token2 }), { error: 'FAILED_PRECONDITION' }, 'nor anything after it');
  assert.ok((await call('removeMember', 'ad', remove('me'))).result, 'and me stays removable');
});

test('D-235 round 2 C1: an account the purge took out (departed) still authors the log', async () => {
  const Z = '5c'.repeat(16);
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam'], createdBy: 'ow', admins: ['ad'],
    createdAt: new Date(), departed: ['zed'] });
  await opAt('zed', `9000-0-${D}`, { op: 'addMember', group: G, member: { id: Z, name: 'Zed' } }, new Date());
  const token = (await call('createInvite', 'me', link(Z))).result.token;
  assert.ok((await call('acceptInvite', 'eve', { token })).result, 'the place zed added is in the log');
});

test('D-235 round 2 m5: someone removed rejoins through an in-app invitation made after the removal', async () => {
  const K = '6d'.repeat(16);
  await op('ad', 11, { op: 'addMember', group: G, member: { id: K, name: 'Mo' } });
  assert.deepEqual((await call('removeMember', 'ad', remove('me'))).result, { removed: true });
  await new Promise((r) => setTimeout(r, 5));
  assert.deepEqual((await call('inviteUser', 'ad', { ...link(K), targetUid: 'me' })).result, { invited: true });
  const [copy] = await list('users/me/invites');
  assert.ok((await call('acceptInvite', 'me', { token: copy.fields.token.stringValue })).result, 'me rejoins at K');
  assert.ok(strings((await read(`groups/${G}`)).memberUids).includes('me'));
  assert.equal((await read(`groups/${G}`)).former.mapValue.fields?.me, undefined);
});

test('D-235 M1: an account\'s own log is read a page at a time, to its end', async () => {
  await alGroup();
  // 250 records before al's two "you" ops (ids sort first): Y is on the second page.
  await seedMany(fillers('users/al/records', 250, '2', { op: 'deviceName', device: D, name: 'Phone' }));
  const r = aliasOf(XA, Y);
  assert.deepEqual((await share('al', [r], SECOND_PHONE)).result, { shared: [r.id], skipped: [] });
});

test('D-235 round 2: past YOUS_MAX records in a co-member\'s own log, what was read is used, never a refusal', async () => {
  await alGroup();
  // 2001 records sort before bo's own ops (`3000-…`): bo's ids are past the cap, so Y reads as nobody's but al's.
  await seedMany(fillers('users/bo/records', 2001, '2', { op: 'deviceName', device: D, name: 'Phone' }, 'bo'));
  await youIn('bo', 1, Y);
  const r = aliasOf(XA, Y);
  assert.deepEqual((await share('al', [r], SECOND_PHONE)).result, { shared: [r.id], skipped: [] });
});

// ---- D-235 round 3 (R2-1, decision 113) ----

test('D-235 round 3 R2-1: adoptRemovals only while the group never had anyone else', async () => {
  const records = () => [removalAt(M.bo, 40)];
  await alone();
  await seed(`invites/used-once`, { groupId: G, memberId: M.bo, createdBy: 'ow', usedBy: 'gone', createdAt: new Date() });
  assert.deepEqual(await call('adoptRemovals', 'ow', { groupId: G, records: records() }), { error: 'FAILED_PRECONDITION' }, 'an invite was used');
  await fetch(`${FS}/v1/${DOCS}/invites/used-once`, { method: 'DELETE', headers: { authorization: 'Bearer owner' } });
  for (const [more, why] of [[{ former: { me: { at: new Date(), by: 'ow' } } }, 'someone was removed'],
    [{ departed: ['zed'] }, 'someone was purged'], [{ createdBy: 'zed' }, 'someone else made it']]) {
    await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow'], createdBy: 'ow', createdAt: new Date(), ...more });
    assert.deepEqual(await call('adoptRemovals', 'ow', { groupId: G, records: records() }), { error: 'FAILED_PRECONDITION' }, why);
  }
  await alone();
  assert.deepEqual((await call('adoptRemovals', 'ow', { groupId: G, records: records() })).result, { adopted: 1 }, 'never anyone else');
  assert.equal((await read(`groups/${G}`)).removals.integerValue, '1', 'counted');
});

test('D-235 round 4 R3-1: placeholder removals are counted and capped at REMOVALS_MAX; an account is always removable, uncounted', async () => {
  assert.deepEqual((await call('removeMember', 'ad', remove('bo', { you: null, records: [removalOf(M.bo)] }))).result, { removed: true });
  assert.equal((await read(`groups/${G}`)).removals.integerValue, '1', 'a placeholder removal is counted');
  // ad (a hostile admin) has filled the counter to 999 with placeholder removals.
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam'], createdBy: 'ow', admins: ['ad'], createdAt: new Date(),
    removals: 999 });
  const P1 = '7a'.repeat(16);
  const P2 = '7b'.repeat(16);
  await op('ad', 21, { op: 'addMember', group: G, member: { id: P1, name: 'P1' } });
  await op('ad', 22, { op: 'addMember', group: G, member: { id: P2, name: 'P2' } });
  const placeholder = (id, records) => call('removeMember', 'ad', { groupId: G, member: id, you: null, memberName: 'P', groupName: 'Goa trip', records });
  assert.deepEqual(await placeholder(P1, [movedFrom(P1, 43), removalAt(P1, 42)]), { error: 'RESOURCE_EXHAUSTED' }, '999 + 2 records');
  assert.equal(await read(`groups/${G}/records/${removalAt(P1, 42).id}`), null, 'nothing written');
  assert.deepEqual((await placeholder(P1, [removalAt(P1, 42)])).result, { removed: true }, '999 + 1');
  assert.equal((await read(`groups/${G}`)).removals.integerValue, '1000');
  assert.deepEqual(await placeholder(P2, [removalAt(P2, 44)]), { error: 'RESOURCE_EXHAUSTED' }, 'a placeholder removal past the cap');
  // The owner removes the admin who filled it: an account, never capped, never counted.
  assert.deepEqual((await call('removeMember', 'ow', remove('ad', { records: [removalAt(M.ad, 45)] }))).result, { removed: true });
  const after = await read(`groups/${G}`);
  assert.equal(after.removals.integerValue, '1000', 'not counted');
  assert.ok(after.former.mapValue.fields.ad);
  assert.deepEqual(strings(after.memberUids), ['ow', 'me', 'sam']);
  await alone();
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow'], createdBy: 'ow', createdAt: new Date(), removals: 1000 });
  assert.deepEqual(await call('adoptRemovals', 'ow', { groupId: G, records: [removalAt(M.bo, 41)] }), { error: 'RESOURCE_EXHAUSTED' }, 'adoptRemovals too');
});

test('D-235 round 4 R3-2: an account that holds a place through an accepted invite is removable even when the log never made it', async () => {
  const P = '7c'.repeat(16); // added by an account whose ops are not read (purged before `departed`): no op in the log
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam', 'eve'], createdBy: 'ow', admins: ['ad'], createdAt: new Date() });
  await joined('eve', P);
  const out = { groupId: G, member: P, you: null, memberName: 'Eve', groupName: 'Goa trip', records: [removalAt(P, 46)] };
  assert.deepEqual((await call('removeMember', 'ad', out)).result, { removed: true });
  assert.ok(!strings((await read(`groups/${G}`)).memberUids).includes('eve'));
  const nobody = { ...out, member: '7d'.repeat(16), records: [removalAt('7d'.repeat(16), 47)] };
  assert.deepEqual(await call('removeMember', 'ad', nobody), { error: 'NOT_FOUND' }, 'a place neither the log nor an account holds');
});

test('D-235 round 3 (decision 113): an account purged before `departed` still authors the log through its used invites and its removals', async () => {
  const Z1 = '5d'.repeat(16);
  const Z2 = '5e'.repeat(16);
  await opAt('zed', `9001-0-${D}`, { op: 'addMember', group: G, member: { id: Z1, name: 'Z1' } }, new Date());
  await opAt('yan', `9002-0-${D}`, { op: 'addMember', group: G, member: { id: Z2, name: 'Z2' } }, new Date());
  const t1 = (await call('createInvite', 'me', link(Z1))).result.token;
  const t2 = (await call('createInvite', 'me', link(Z2))).result.token;
  assert.deepEqual(await call('acceptInvite', 'eve', { token: t1 }), { error: 'FAILED_PRECONDITION' }, 'zed is no author yet');
  // zed made an invite someone used; yan wrote a removal (both long gone, no `departed`).
  await seed('invites/zed-made', { groupId: G, memberId: M.sam, createdBy: 'zed', usedBy: 'sam', createdAt: new Date() });
  await seed(`groups/${G}/records/9003-0-${D}`, { ...{
    type: '3', hlc: { millis: 9003, counter: 0, device: D }, authorUid: 'yan', deviceId: D, createdAt: new Date(), removal: true,
    payload: { version: 1, epoch: 0, flags: 0, nonce: new Uint8Array([1]), blob: bytes({ op: 'removeMember', group: G, member: M.bo }) } } });
  assert.ok((await call('acceptInvite', 'eve', { token: t1 })).result, 'the place zed added: zed made a used invite');
  await seed('users/fay', { participantId: 'f9'.repeat(16), name: 'Fay' });
  assert.ok((await call('acceptInvite', 'fay', { token: t2 })).result, 'the place yan added: yan wrote a removal');
});

// ---- D-237 (the Fable audit, A3): who a participant id belongs to ----
//
// Participant ids are not unique (a phone writes its profile's own), so
// decision 47's "another account's participant id" is decided by who
// REGISTERED it first (users/{uid}.createdAt, the server's time, written by
// the rules at the profile's creation). Before this, one profile made with a
// copied id refused every held-place mark (D-230) and alias repair of the
// person it copied, and made them un-invitable through inviteKnown (G-29).

test('D-237 A3: the account that registered an id first keeps its marks; a later profile with the same id marks nothing with it', async () => {
  // sam registered a day ago (round 2, review M5: two real timestamps); cop's profile copies sam's participant id (a
  // modified phone; the rules take any 32 hex), registered now.
  await seed('users/sam', { participantId: P.sam, name: NAME.sam, createdAt: new Date(Date.now() - 86_400_000) });
  await seed('users/cop', { participantId: P.sam, name: 'Cop', createdAt: new Date() });
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam', 'cop'], createdBy: 'ow', admins: ['ad'], createdAt: new Date() });
  await joined('cop', M.bo);
  const sams = heldNowIn(G, M.sam, P.sam, 1);
  assert.deepEqual((await place('sam', [sams])).result, { shared: [sams.id], skipped: [] }, 'sam registered the id first');
  const cops = heldNowIn(G, M.bo, P.sam, 2);
  assert.deepEqual((await place('cop', [cops])).result, { shared: [], skipped: [cops.id] }, 'the copy marks nothing with it');
  assert.deepEqual((await heldIn(G)).map((h) => [h.member, h.you, h.author]), [[M.sam, P.sam, 'sam']]);
  // An id copied only into a log (no registration) is another's as soon as anyone registered it, as before.
  await youIn('me', 0, P.sam);
  const mes = heldNowIn(G, M.me, P.sam, 3);
  assert.deepEqual((await place('me', [mes])).result, { shared: [], skipped: [mes.id] }, 'a log copy owns nothing');
  assert.deepEqual((await heldIn(G)).length, 1);
  // A profile registered EARLIER than the caller's own, with the caller's id: the caller's mark is refused (the
  // same phone's second account, G-31 (a), fails closed as before).
  await seed('users/me', { participantId: P.me, name: NAME.me, createdAt: new Date(Date.now() - 86_400_000) });
  await seed('users/first', { participantId: P.me, name: 'First', createdAt: new Date(Date.now() - 2 * 86_400_000) });
  const mine = heldNowIn(G, M.me, P.me, 4);
  assert.deepEqual((await place('me', [mine])).result, { shared: [], skipped: [mine.id] }, 'an earlier registration wins');
  // Round 2 (review I2): twenty or more profiles holding an id is a full read, which fails closed for everyone,
  // the real registrant included (never a copy sneaking past an unread registrant).
  await seed('users/ad', { participantId: P.ad, name: NAME.ad, createdAt: new Date(Date.now() - 86_400_000) });
  for (let i = 0; i < 20; i += 1) await seed(`users/a${String(i).padStart(2, '0')}`, { participantId: P.ad, name: 'Copy', createdAt: new Date() });
  const ads = heldNowIn(G, M.ad, P.ad, 5);
  assert.deepEqual((await place('ad', [ads])).result, { shared: [], skipped: [ads.id] }, 'a full read fails closed');
  await seed(`groups/${G}`, { name: 'Goa trip', memberUids: ['ow', 'ad', 'me', 'sam', 'cop', 'a00'], createdBy: 'ow', admins: ['ad'], createdAt: new Date() });
  await joined('a00', M.bo);
  const copy = heldNowIn(G, M.bo, P.ad, 6);
  assert.deepEqual((await place('a00', [copy])).result, { shared: [], skipped: [copy.id] }, 'and for the copies');
});

test('D-237 A3: decision 47 the same way for aliases: the first registration keeps its repair, the later copy is refused', async () => {
  await alGroup();
  await seed('users/cop', { participantId: PID.bo, name: 'Cop', createdAt: new Date() });
  await youIn('cop', 0, PID.bo);
  const bos = aliasOf(BP, PID.bo, 1);
  assert.deepEqual((await share('bo', [bos])).result, { shared: [bos.id], skipped: [] }, 'bo registered the id first');
  const cops = aliasOf(BP, PID.bo, 2);
  assert.deepEqual((await share('cop', [cops])).result, { shared: [], skipped: [cops.id] }, 'the copy repairs nothing');
  assert.deepEqual((await aliasesIn()).map((a) => [a.member, a.you, a.author]), [[BP, PID.bo, 'bo']]);
});

test('D-237 A3 (G-29): an outsider who copies a member\'s participant id no longer makes that member un-invitable', async () => {
  const V = 'e'.repeat(32);
  const PK = '7'.repeat(32);
  await seed(`groups/${V}`, { name: 'Flat', memberUids: ['me', 'kit'], createdBy: 'me', createdAt: new Date() });
  await seed('users/kit', { name: 'Kit', findable: false, participantId: PK });
  await seed('users/out', { name: 'Out', findable: true, participantId: PK, createdAt: new Date() });
  assert.deepEqual((await call('inviteKnown', 'me', { ...link(M.bo), viaGroupId: V, participantId: PK })).result, { invited: true });
  assert.equal((await list('users/kit/invites')).length, 1, 'delivered to the member');
  assert.equal((await list('users/out/invites')).length, 0, 'never to the outsider');
  // A copier who is in the via group too: the earlier registration is the person.
  await seed(`groups/${V}`, { name: 'Flat', memberUids: ['me', 'kit', 'out'], createdBy: 'me', createdAt: new Date() });
  assert.deepEqual((await call('inviteKnown', 'me', { ...link(M.bo), viaGroupId: V, participantId: PK })).result, { invited: true });
  assert.equal((await list('users/kit/invites')).length, 2);
  assert.equal((await list('users/out/invites')).length, 0);
});

// ---- D-257 (G-04): a group's daily removals, on limits/group-{groupId}, charged with the removal ----
const GL = `limits/group-${G}`;
const TODAY = () => new Date().toISOString().slice(0, 10);
const YESTERDAY = () => new Date(Date.now() - 86400000).toISOString().slice(0, 10);
const removalsToday = async () => Number((await read(GL))?.groupRemovals?.integerValue ?? 0);

test('D-257: removeMember charges the group\'s day with the removal; a refused call spends none; a full day refuses', async () => {
  assert.deepEqual(await call('removeMember', 'me', remove('sam')), { error: 'PERMISSION_DENIED' });
  assert.equal(await read(GL), null, 'a plain member\'s refused call spends none of the group\'s removals');
  assert.deepEqual((await call('removeMember', 'ad', remove('sam'))).result, { removed: true });
  assert.equal(await removalsToday(), 1);
  await seed(GL, { day: TODAY(), groupRemovals: 100, groupInvites: 7 });
  const recordsBefore = (await list(`groups/${G}/records`)).length;
  const meOut = remove('me', { records: [movedFrom(M.me, 3), removalAt(M.me, 4)] });
  assert.deepEqual(await call('removeMember', 'ow', meOut), { error: 'RESOURCE_EXHAUSTED' });
  assert.deepEqual(strings((await read(`groups/${G}`)).memberUids), ['ow', 'ad', 'me'], 'nobody removed');
  assert.equal((await list(`groups/${G}/records`)).length, recordsBefore, 'no record written');
  assert.equal(await removalsToday(), 100);
  await seed(GL, { day: YESTERDAY(), groupRemovals: 100 });
  assert.deepEqual((await call('removeMember', 'ow', meOut)).result, { removed: true }, 'the next UTC day');
  assert.equal(await removalsToday(), 1);
});

test('D-257: adoptRemovals charges the group\'s day, one a call; a full day refuses, nothing written', async () => {
  await alone();
  await seed(GL, { day: TODAY(), groupRemovals: 100 });
  const records = [removalOf(M.sam), movedFrom(M.sam)];
  assert.deepEqual(await call('adoptRemovals', 'ow', { groupId: G, records }), { error: 'RESOURCE_EXHAUSTED' });
  assert.equal(await read(`groups/${G}/records/${removalOf(M.sam).id}`), null, 'nothing written');
  await seed(GL, { day: TODAY(), groupRemovals: 99 });
  assert.deepEqual((await call('adoptRemovals', 'ow', { groupId: G, records })).result, { adopted: 2 });
  assert.equal(await removalsToday(), 100, 'one a call, whatever its records');
});
