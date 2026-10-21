// D-198: your phones and deleting your account. The rules
// (firebase/functions/account.js) tested directly, and the deleting
// (account-store.js) run against the Firestore and Auth emulators, then the
// store is QUERIED to prove what is gone (SPEC §4.4: "verified by a test that
// queries the store afterwards, not by trusting the ORM").
import { test, before, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import * as account from '../functions/account.js';
import { createHash } from 'node:crypto';
import { purgeAccount, purgeDue, deleteGroupPicture, signOutPhones } from '../functions/account-store.js';
import { initializeApp } from '../functions/node_modules/firebase-admin/lib/esm/app/index.js';
import { getFirestore } from '../functions/node_modules/firebase-admin/lib/esm/firestore/index.js';
import { getAuth } from '../functions/node_modules/firebase-admin/lib/esm/auth/index.js';
import { getStorage } from '../functions/node_modules/firebase-admin/lib/esm/storage/index.js';

const PROJECT = 'demo-skipti';
const FS = `http://127.0.0.1:${process.env.FIRESTORE_PORT ?? 8180}`;
const AUTH = `http://127.0.0.1:${process.env.AUTH_PORT ?? 9099}`;
const DOCS = `projects/${PROJECT}/databases/(default)/documents`;
const D1 = 'a'.repeat(32);
const D2 = 'b'.repeat(32);

let db;
let auth;
let bucket;
before(() => {
  const app = initializeApp({ projectId: PROJECT }, 'account-test');
  db = getFirestore(app);
  auth = getAuth(app);
  bucket = getStorage(app).bucket(`${PROJECT}.appspot.com`);
});
beforeEach(async () => {
  assert.equal((await fetch(`${FS}/emulator/v1/${DOCS}`, { method: 'DELETE' })).status, 200);
  await fetch(`${AUTH}/emulator/v1/projects/${PROJECT}/accounts`, { method: 'DELETE' });
});

// ---- the rules ----

test('deletion is 7 days after it is asked, and due only then', () => {
  const now = Date.UTC(2026, 9, 2, 12);
  assert.equal(account.deletionAt(now) - now, 7 * 24 * 3600 * 1000);
  assert.equal(account.due({ deleteAt: account.deletionAt(now) }, now), false);
  assert.equal(account.due({ deleteAt: account.deletionAt(now) }, account.deletionAt(now)), true);
  assert.equal(account.due({ deleteAt: { toMillis: () => now - 1 } }, now), true);
  assert.equal(account.due({}, now), false);
  assert.equal(account.due(null, now), false);
});

test('a deleted account leaves groups with others and destroys those where it was the last', () => {
  assert.deepEqual(account.groupsPlan([
    { id: 'g1', memberUids: ['al', 'bo'] },
    { id: 'g2', memberUids: ['al'] },
    { id: 'g3', memberUids: ['bo'] },
    { id: 'g4', memberUids: ['al', 'al'] },
  ], 'al'), { leave: ['g1'], destroy: ['g2', 'g4'], handOver: [] });
  assert.deepEqual(account.groupsPlan([], 'al'), { leave: [], destroy: [], handOver: [] });
});

test('a new phone\'s sign-in is told to the other phones only, with a push address, not signed out', () => {
  assert.deepEqual(account.otherPhones([
    { id: 'new', data: { fcmToken: 't0' } },
    { id: 'p1', data: { fcmToken: 't1' } },
    { id: 'p2', data: { fcmToken: 't2', signedOut: true } },
    { id: 'p3', data: {} },
  ], 'new'), [{ token: 't1', id: 'p1' }]);
  assert.deepEqual(account.devicePush('Pixel 8').notification,
    { title: 'SKIPTI', body: 'Pixel 8 signed in to your account. Was this you?' });
  assert.equal(account.devicePush('').notification.body, 'A new phone signed in to your account. Was this you?');
  assert.deepEqual(account.devicePush('x').data, { kind: 'device' });
  assert.equal(account.deviceId(D1), true);
  assert.equal(account.deviceId('phone1'), false);
});

// ---- the store, then queried ----

async function seedAccount() {
  await db.doc('users/al').set({ participantId: D1, name: 'Al', username: 'al_x', findable: true, emailHash: 'f'.repeat(64) });
  await db.doc(`users/al/devices/${D1}`).set({ name: 'iPhone', platform: 'ios', fcmToken: 't1' });
  await db.doc(`users/al/devices/${D2}`).set({ name: 'Pixel 8', platform: 'android', fcmToken: 't2' });
  await db.doc('users/al/records/r1').set({ type: '1', authorUid: 'al' });
  await db.doc('users/al/invites/i1').set({ groupName: 'Ski week' });
  await db.doc('usernames/al_x').set({ uid: 'al' });
  await db.doc('limits/al').set({ day: '2026-10-02', searches: 3 });
  await db.doc('groups/shared').set({ name: 'Goa trip', memberUids: ['al', 'bo'] });
  await db.doc('groups/shared/records/s1').set({ type: '1', authorUid: 'al' });
  await db.doc('groups/alone').set({ name: 'Mine', memberUids: ['al'] });
  await db.doc('groups/alone/records/a1').set({ type: '1', authorUid: 'al' });
  await db.doc('groups/theirs').set({ name: 'Bo only', memberUids: ['bo'] });
  await db.doc('invites/h-unused').set({ createdBy: 'al', usedBy: null });
  await db.doc('invites/h-used').set({ createdBy: 'al', usedBy: 'bo' });
  await db.doc('invites/h-bo').set({ createdBy: 'bo', usedBy: null });
  await db.doc('users/bo').set({ participantId: D2, name: 'Bo' });
  await auth.createUser({ uid: 'al' });
  await auth.createUser({ uid: 'bo' });
}

test('deleting an account removes everything of it, and nothing of anyone else (queried afterwards)', async () => {
  await seedAccount();
  assert.deepEqual(await purgeAccount(db, auth, 'al', bucket), { leave: ['shared'], destroy: ['alone'], handOver: [] });
  const exists = async (path) => (await db.doc(path).get()).exists;
  for (const gone of ['users/al', `users/al/devices/${D1}`, `users/al/devices/${D2}`, 'users/al/records/r1',
    'users/al/invites/i1', 'usernames/al_x', 'limits/al', 'groups/alone', 'groups/alone/records/a1', 'invites/h-unused']) {
    assert.equal(await exists(gone), false, gone + ' is gone');
  }
  assert.deepEqual((await db.doc('groups/shared').get()).data().memberUids, ['bo'], 'the shared group survives without al');
  assert.equal(await exists('groups/shared/records/s1'), true, 'what al added stays for the group');
  for (const kept of ['groups/theirs', 'users/bo', 'invites/h-bo', 'invites/h-used']) {
    assert.equal(await exists(kept), true, kept + ' is not al\'s');
  }
  assert.equal((await db.collection('users').doc('al').collection('records').get()).size, 0);
  assert.equal((await db.collection('groups').where('memberUids', 'array-contains', 'al').get()).size, 0);
  await assert.rejects(auth.getUser('al'), (e) => e.code === 'auth/user-not-found');
  assert.equal((await auth.getUser('bo')).uid, 'bo');
  assert.deepEqual(await purgeAccount(db, auth, 'al', bucket), { leave: [], destroy: [], handOver: [] }, 'twice is harmless');
});

test('D-211 (founder decision 5): deleting an account deletes its previews in the groups it leaves, and only its own', async () => {
  await seedAccount();
  await db.doc('groups/two').set({ name: 'Flat', memberUids: ['bo', 'al'] });
  await db.doc('groups/shared/records/p-al').set({ type: '3', authorUid: 'al', profile: true });
  await db.doc('groups/shared/records/p-al-2').set({ type: '3', authorUid: 'al', profile: true });
  await db.doc('groups/two/records/p-al').set({ type: '3', authorUid: 'al', profile: true });
  await db.doc('groups/shared/records/p-bo').set({ type: '3', authorUid: 'bo', profile: true });
  await db.doc('groups/shared/records/r-al').set({ type: '3', authorUid: 'al' });
  assert.deepEqual((await purgeAccount(db, auth, 'al', bucket)).leave, ['shared', 'two']);
  const exists = async (path) => (await db.doc(path).get()).exists;
  for (const gone of ['groups/shared/records/p-al', 'groups/shared/records/p-al-2', 'groups/two/records/p-al']) {
    assert.equal(await exists(gone), false, gone + ' is gone');
  }
  assert.equal(await exists('groups/shared/records/p-bo'), true, 'another member\'s preview stays');
  assert.equal(await exists('groups/shared/records/r-al'), true, 'what al added that is not a preview stays');
  assert.equal(await exists('groups/shared/records/s1'), true);
});

test('D-210: a deleted owner hands the group on, and a destroyed group\'s picture goes too', async () => {
  await seedAccount();
  await db.doc('groups/owned').set({ name: 'Ski week', memberUids: ['al', 'bo', 'cy'], createdBy: 'al', admins: ['al', 'cy'] });
  await db.doc('groups/handed').set({ name: 'Flat', memberUids: ['bo', 'al'], owner: 'al' });
  await db.doc('groups/kept').set({ name: 'Bo\'s', memberUids: ['bo', 'al'], owner: 'bo', admins: ['al'] });
  const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xd9]);
  await bucket.file('groups/alone/picture.jpg').save(jpeg, { contentType: 'image/jpeg' });
  await bucket.file('groups/shared/picture.jpg').save(jpeg, { contentType: 'image/jpeg' });
  const plan = await purgeAccount(db, auth, 'al', bucket);
  assert.deepEqual(plan.handOver, [{ id: 'handed', owner: 'bo' }, { id: 'owned', owner: 'cy' }], 'in the query\'s (id) order');
  const owned = (await db.doc('groups/owned').get()).data();
  assert.equal(owned.owner, 'cy', 'the admin left becomes the owner');
  assert.deepEqual(owned.admins, [], 'al is no admin, and the owner holds one seat, not two');
  assert.deepEqual(owned.memberUids, ['bo', 'cy']);
  assert.equal((await db.doc('groups/handed').get()).data().owner, 'bo', 'no admin: the first member left');
  const kept = (await db.doc('groups/kept').get()).data();
  assert.equal(kept.owner, 'bo', 'not al\'s to hand on');
  assert.deepEqual(kept.admins, [], 'al leaves the admins');
  assert.deepEqual((await bucket.file('groups/alone/picture.jpg').exists())[0], false, 'the destroyed group\'s picture');
  assert.deepEqual((await bucket.file('groups/shared/picture.jpg').exists())[0], true, 'a surviving group keeps its picture');
  await bucket.file('groups/shared/picture.jpg').delete();
});

test('D-210 final (A5): a deleted owner\'s hand-over closes an open request about the new owner, and its notices', async () => {
  await seedAccount();
  const place = 'c'.repeat(32);
  const other = 'd'.repeat(32);
  await db.doc('users/cy').set({ participantId: 'e'.repeat(32), name: 'Cy' });
  await db.doc('groups/owned').set({ name: 'Ski week', memberUids: ['al', 'bo', 'cy'], createdBy: 'al', admins: ['cy'] });
  await db.doc(`groups/owned/requests/${place}`).set({ member: place, you: 'e'.repeat(32), memberName: 'Cy', askerUid: 'bo', askerName: 'Bo' });
  await db.doc(`groups/owned/requests/${other}`).set({ member: other, you: null, memberName: 'Di', askerUid: 'bo', askerName: 'Bo' });
  for (const u of ['bo', 'cy']) {
    await db.doc(`users/${u}/notices/request-owned-${place}`).set({ kind: 'request', groupId: 'owned', memberId: place });
    await db.doc(`users/${u}/notices/request-owned-${other}`).set({ kind: 'request', groupId: 'owned', memberId: other });
  }
  const plan = await purgeAccount(db, auth, 'al', bucket);
  assert.deepEqual(plan.handOver, [{ id: 'owned', owner: 'cy' }]);
  assert.equal((await db.doc(`groups/owned/requests/${place}`).get()).exists, false, 'nobody asks to remove the owner');
  assert.equal((await db.doc(`groups/owned/requests/${other}`).get()).exists, true, 'another request stays');
  for (const u of ['bo', 'cy']) {
    assert.equal((await db.doc(`users/${u}/notices/request-owned-${place}`).get()).exists, false, u);
    assert.equal((await db.doc(`users/${u}/notices/request-owned-${other}`).get()).exists, true, u);
  }
});

test('M2: a hand-over\'s closing of requests that throws never stops the purge or the other hand-overs', async () => {
  await seedAccount();
  await db.doc('users/cy').set({ participantId: 'e'.repeat(32), name: 'Cy' });
  await db.doc('groups/owned').set({ name: 'Ski week', memberUids: ['al', 'bo', 'cy'], createdBy: 'al', admins: ['cy'] });
  await db.doc('groups/owned2').set({ name: 'Flat', memberUids: ['al', 'bo'], createdBy: 'al' });
  // The seam: reading any group's open requests fails, as a Firestore outage would.
  const wrap = (target, over) => new Proxy(target, {
    get(t, k) { const v = t[k]; return over[k] ? over[k](t) : typeof v === 'function' ? v.bind(t) : v; },
  });
  const flaky = wrap(db, {
    collection: (t) => (name) => {
      const c = t.collection(name);
      return name !== 'groups' ? c : wrap(c, { doc: (ct) => (id) => wrap(ct.doc(id), {
        collection: (dt) => (sub) => (sub === 'requests' ? { get: async () => { throw new Error('unavailable'); } } : dt.collection(sub)),
      }) });
    },
  });
  const plan = await purgeAccount(flaky, auth, 'al', bucket);
  assert.deepEqual(plan.handOver.map((h) => h.id), ['owned', 'owned2'], 'both groups were handed on');
  assert.equal((await db.doc('groups/owned').get()).data().owner, 'cy');
  assert.equal((await db.doc('groups/owned2').get()).data().owner, 'bo');
  assert.equal((await db.doc('users/al').get()).exists, false, 'the purge went on to the end');
  await assert.rejects(auth.getUser('al'), 'the Auth user is gone too');
});

test('D-210 final (A3): a picture that cannot be deleted never stops a purge', async () => {
  await seedAccount();
  const refused = { file: () => ({ delete: async () => { throw Object.assign(new Error('Forbidden'), { code: 403 }); } }) };
  assert.deepEqual(await purgeAccount(db, auth, 'al', refused), { leave: ['shared'], destroy: ['alone'], handOver: [] });
  assert.equal((await db.doc('groups/alone').get()).exists, false, 'the group still goes');
  assert.equal((await db.doc('users/al').get()).exists, false, 'and the account');
  await assert.rejects(auth.getUser('al'), (e) => e.code === 'auth/user-not-found');
  // No bucket given and none to be had (this process has no default app):
  // resolved only when a picture is deleted, and the failure is logged.
  await db.doc('groups/alone').set({ name: 'Mine', memberUids: ['al'] });
  await assert.doesNotReject(purgeAccount(db, auth, 'al'));
  assert.equal((await db.doc('groups/alone').get()).exists, false);
  assert.equal(await deleteGroupPicture('alone', refused), false);
  assert.equal(await deleteGroupPicture('alone', null), false);
  assert.equal(await deleteGroupPicture('alone', bucket), true, 'a missing picture is not a failure');
});

test('D-210 final (A3): one account that fails to purge is logged, and the others are still purged', async () => {
  await seedAccount();
  const past = new Date(Date.now() - 1000);
  await db.doc('users/al').set({ deleteAt: past }, { merge: true });
  await db.doc('users/bo').set({ deleteAt: new Date(Date.now() - 500) }, { merge: true });
  await db.doc('users/cy').set({ deleteAt: new Date(Date.now() + 3600000), name: 'Cy' });
  // al's Auth user cannot be deleted (anything but "not found" is a failure).
  const flaky = {
    deleteUser: async (uid) => {
      if (uid === 'al') throw Object.assign(new Error('internal'), { code: 'auth/internal-error' });
      return auth.deleteUser(uid);
    },
  };
  assert.deepEqual(await purgeDue(db, flaky, Date.now(), bucket), { purged: ['bo'], failed: ['al'], more: false });
  assert.equal((await db.doc('users/bo').get()).exists, false, 'bo, after al failed');
  await assert.rejects(auth.getUser('bo'), (e) => e.code === 'auth/user-not-found');
  assert.equal((await db.doc('users/cy').get()).exists, true, 'not due yet');
});

test('signing phones out flags them with the time and drops their push addresses', async () => {
  await seedAccount();
  assert.equal(await signOutPhones(db, 'al', D2), 1);
  const one = (await db.doc(`users/al/devices/${D2}`).get()).data();
  assert.equal(one.signedOut, true);
  assert.ok(one.signedOutAt, 'with the time');
  assert.equal(one.fcmToken, undefined);
  assert.equal((await db.doc(`users/al/devices/${D1}`).get()).data().signedOut, undefined, 'the other is untouched');
  assert.equal(await signOutPhones(db, 'al', 'c'.repeat(32)), 0, 'no such phone');
  assert.equal(await signOutPhones(db, 'al'), 2);
  assert.equal((await db.doc(`users/al/devices/${D1}`).get()).data().signedOut, true);
});

// ---- D-235 (L2, L4) ----

test('D-235 L2: a deleted account\'s unused links go with their in-app copies; used ones keep no name of it', async () => {
  await seedAccount();
  const token = 'T'.repeat(43);
  const hash = createHash('sha256').update(token).digest('hex');
  await db.doc(`invites/${hash}`).set({ createdBy: 'al', usedBy: null, groupId: 'shared', deliveredTo: 'bo', inviterName: 'Al' });
  await db.doc('users/bo/invites/copy').set({ groupId: 'shared', token, fromName: 'Al' });
  await db.doc('users/bo/invites/keep').set({ groupId: 'shared', token: 'K'.repeat(43), fromName: 'Cy' });
  await db.doc('invites/h-used').set({ createdBy: 'al', usedBy: 'bo', inviterName: 'Al', memberName: 'Bo' });
  await db.doc('invites/h-joined').set({ createdBy: 'bo', usedBy: 'al', inviterName: 'Bo', memberName: 'Al' });
  await purgeAccount(db, auth, 'al', bucket);
  assert.equal((await db.doc(`invites/${hash}`).get()).exists, false, 'the unused link');
  assert.equal((await db.doc('users/bo/invites/copy').get()).exists, false, 'its copy on bo\'s account');
  assert.equal((await db.doc('users/bo/invites/keep').get()).exists, true, 'another invitation of bo\'s stays');
  const made = (await db.doc('invites/h-used').get()).data();
  assert.equal(made.inviterName, undefined, 'a used link al made keeps no name of al\'s');
  assert.equal(made.memberName, 'Bo', 'nor loses bo\'s');
  assert.equal(made.usedBy, 'bo');
  const joined = (await db.doc('invites/h-joined').get()).data();
  assert.equal(joined.memberName, undefined, 'the place al joined as is no longer named');
  assert.equal(joined.inviterName, 'Bo');
  assert.equal(joined.usedBy, 'al', 'the history (who joined) stays');
});

test('D-235 L4: the purge pages past accounts that fail, and says when it stopped with accounts still due', async () => {
  await seedAccount();
  await db.doc('users/al').set({ deleteAt: new Date(Date.now() - 3000) }, { merge: true });
  await db.doc('users/bo').set({ deleteAt: new Date(Date.now() - 2000) }, { merge: true });
  await db.doc('users/cy').set({ deleteAt: new Date(Date.now() - 1000), name: 'Cy' });
  await auth.createUser({ uid: 'cy' });
  const flaky = {
    deleteUser: async (uid) => {
      if (uid === 'al') throw Object.assign(new Error('internal'), { code: 'auth/internal-error' });
      return auth.deleteUser(uid);
    },
  };
  // One account a page: al (the oldest) fails on the first page, and the run goes on to the next two.
  assert.deepEqual(await purgeDue(db, flaky, Date.now(), bucket, { pageSize: 1 }), { purged: ['bo', 'cy'], failed: ['al'], more: false });
  assert.equal((await db.doc('users/cy').get()).exists, false);
  // A run bounded at one page of one stops with the next still due, and says so.
  await db.doc('users/dee').set({ deleteAt: new Date(Date.now() - 2000), name: 'Dee' });
  await db.doc('users/eli').set({ deleteAt: new Date(Date.now() - 1000), name: 'Eli' });
  assert.deepEqual(await purgeDue(db, auth, Date.now(), bucket, { pageSize: 1, maxPages: 1 }), { purged: ['dee'], failed: [], more: true });
  assert.deepEqual(await purgeDue(db, auth, Date.now(), bucket, { pageSize: 1, maxPages: 1 }), { purged: ['eli'], failed: [], more: false });
});

test('D-235 round 2 C1: a deleted account that leaves a group is kept as `departed`, so its records there keep an author', async () => {
  await seedAccount();
  await purgeAccount(db, auth, 'al', bucket);
  const shared = (await db.doc('groups/shared').get()).data();
  assert.deepEqual(shared.memberUids, ['bo']);
  assert.deepEqual(shared.departed, ['al']);
  assert.equal((await db.doc('groups/theirs').get()).data().departed, undefined, 'a group it was not in');
});
