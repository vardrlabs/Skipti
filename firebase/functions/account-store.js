// D-198: deleting an account for good (SPEC §4.4), and signing phones out.
// The Firestore and Auth handles are passed in, so firebase/tests/
// account.test.mjs runs exactly this code against the emulators and then
// queries the store to prove what is gone.
import { createHash } from 'node:crypto';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import * as logger from 'firebase-functions/logger';
import * as account from './account.js';
import * as approvals from './approvals.js';
import * as groups from './groups.js';

/** Deletes group `id`'s picture. `bucket` is the Storage bucket, or null for
 *  the project's default, resolved here and only here: a project with no
 *  bucket, a refusal (403) or any other failure is logged and answered
 *  false, never thrown, so it never fails what called it (deleteGroup, the
 *  purge). A picture that is not there is fine. */
export async function deleteGroupPicture(id, bucket = null) {
  try {
    const b = bucket ?? getStorage().bucket();
    await b.file(`groups/${id}/picture.jpg`).delete({ ignoreNotFound: true });
    return true;
  } catch (e) {
    logger.warn('group picture: not deleted', { code: typeof e?.code === 'string' || typeof e?.code === 'number' ? e.code : 'other' });
    return false;
  }
}

/** D-235 (L2): deletes unused link invites (`docs`, invites/{hash}
 *  snapshots) and each one's in-app copy on the account it was delivered to
 *  (users/{deliveredTo}/invites, the copy whose token hashes to the link's
 *  id), so nobody keeps a card whose Join can only fail. */
export async function voidLinks(db, docs) {
  await Promise.all(docs.map(async (d) => {
    const { deliveredTo, groupId } = d.data();
    if (typeof deliveredTo === 'string' && deliveredTo && typeof groupId === 'string') {
      const copies = await db.collection('users').doc(deliveredTo).collection('invites').where('groupId', '==', groupId).get();
      await Promise.all(copies.docs.filter((c) => typeof c.data().token === 'string' && hashToken(c.data().token) === d.id)
        .map((c) => c.ref.delete()));
    }
    await d.ref.delete();
  }));
}

const hashToken = (token) => createHash('sha256').update(token, 'utf8').digest('hex');

/** Marks the account's phones signed out (all, or the one named), with the
 *  time, and drops their push addresses. Returns how many. */
export async function signOutPhones(db, uid, only = null) {
  const phones = db.collection('users').doc(uid).collection('devices');
  const docs = only ? [await phones.doc(only).get()].filter((d) => d.exists) : (await phones.get()).docs;
  await Promise.all(docs.map((d) => d.ref.update({
    signedOut: true, signedOutAt: FieldValue.serverTimestamp(), fcmToken: FieldValue.delete(),
  })));
  return docs.length;
}

/** Deletes everything of `uid`: its groups (left, or destroyed when it was
 *  the last member, with the group's picture; D-210: a group it owned is
 *  handed on; D-211: its previews in the groups it leaves are deleted), its
 *  profile with records, phones, invitations and notices, its
 *  username, its counters, its approval (D-254), its unused link invites,
 *  and the Auth user.
 *  `bucket` is the Storage bucket of group pictures (the tests pass the
 *  emulator's), or null for the default, resolved only when a picture is
 *  deleted (deleteGroupPicture). An open removal request about a group's new
 *  owner is closed with its notices (nobody asks to remove the owner). */
export async function purgeAccount(db, auth, uid, bucket = null) {
  const groupDocs = (await db.collection('groups').where('memberUids', 'array-contains', uid).get()).docs;
  const plan = account.groupsPlan(groupDocs.map((d) => {
    const { memberUids, owner, createdBy, admins } = d.data();
    return { id: d.id, memberUids, owner, createdBy, admins };
  }), uid);
  const owners = new Map(plan.handOver.map((h) => [h.id, h.owner]));
  for (const id of plan.leave) {
    // Founder decision 5 (D-211): its previews go from every group it leaves,
    // before it leaves: a purge that fails after this is retried, and the
    // retry finds only the groups it is still in.
    const marked = await db.collection('groups').doc(id).collection('records').where('profile', '==', true).get();
    await Promise.all(marked.docs.filter((d) => d.data().authorUid === uid).map((d) => d.ref.delete()));
    await db.collection('groups').doc(id).update({
      // The new owner leaves the admins, as makeOwner does: one seat each.
      memberUids: FieldValue.arrayRemove(uid),
      // D-235 round 2 (C1): the log's records it wrote stay, and opsOf reads
      // a log by author: `departed` keeps it an author (Functions only write
      // it; the group rules let a phone change only the name and emoji).
      departed: FieldValue.arrayUnion(uid),
      ...(owners.has(id)
        ? { owner: owners.get(id), admins: FieldValue.arrayRemove(uid, owners.get(id)) }
        : { admins: FieldValue.arrayRemove(uid) }),
    });
  }
  for (const { id, owner } of plan.handOver) {
    // The hand-over is committed and nothing retries this: a failure to close
    // its requests is logged, and the purge goes on (D-210 M2).
    try {
      await closeRequestsAbout(db, id, owner);
    } catch (e) {
      logger.warn('purge: closing requests about a new owner failed', { code: typeof e?.code === 'string' || typeof e?.code === 'number' ? e.code : 'other' });
    }
  }
  for (const id of plan.destroy) {
    await db.recursiveDelete(db.collection('groups').doc(id));
    await deleteGroupPicture(id, bucket);
  }
  const userRef = db.collection('users').doc(uid);
  const profile = await userRef.get();
  const handle = profile.exists ? profile.data().username : null;
  if (typeof handle === 'string') {
    const owner = await db.collection('usernames').doc(handle).get();
    if (owner.exists && owner.data().uid === uid) await owner.ref.delete();
  }
  await db.recursiveDelete(userRef);
  await db.collection('limits').doc(uid).delete();
  // D-254: its approval (a request's name and email, or a decision) goes too.
  await db.collection('approvals').doc(uid).delete();
  const links = await db.collection('invites').where('createdBy', '==', uid).get();
  // D-235 (L2): its unused links go with their in-app copies on the accounts
  // they were sent to; the used ones it made, and the ones it used, stay as
  // the groups' history without its name (nothing reads a used invite's
  // names: peekInvite opens unused ones only).
  await voidLinks(db, links.docs.filter((d) => !d.data().usedBy));
  await Promise.all(links.docs.filter((d) => d.data().usedBy).map((d) => d.ref.update({ inviterName: FieldValue.delete() })));
  const used = await db.collection('invites').where('usedBy', '==', uid).get();
  await Promise.all(used.docs.map((d) => d.ref.update({ memberName: FieldValue.delete() })));
  try {
    await auth.deleteUser(uid);
  } catch (e) {
    if (e?.code !== 'auth/user-not-found') throw e;
  }
  return plan;
}

/** D-254 fix wave (M1, M2): the approval migration's write for one page of
 *  candidate accounts (`uids`, at most approvals.BATCH: profiles whose
 *  approval had no decision when the page was read). One transaction reads
 *  every profile and approval again and approves only a uid whose profile
 *  still exists and whose approval still has no decision
 *  (approvals.migrates), so a denial made since is never overwritten and a
 *  purged account's approval is never made again; two runs at once are
 *  serialised by it. Returns how many this call approved. */
export async function approveFromBeforeGate(db, uids) {
  if (!uids.length) return 0;
  const users = uids.map((uid) => db.collection('users').doc(uid));
  const refs = uids.map((uid) => db.collection('approvals').doc(uid));
  return db.runTransaction(async (tx) => {
    const profiles = await tx.getAll(...users);
    const now = await tx.getAll(...refs);
    let n = 0;
    uids.forEach((uid, i) => {
      if (!profiles[i].exists) return;
      const approval = now[i].exists ? now[i].data() : null;
      if (!approvals.migrates(approval)) return;
      const createdAt = profiles[i].data().createdAt;
      const requestedAt = approval?.requestedAt
        ?? (typeof createdAt?.toMillis === 'function' ? createdAt : FieldValue.serverTimestamp());
      tx.set(refs[i], {
        state: 'approved', requestedAt, decidedAt: FieldValue.serverTimestamp(), decidedBy: 'migration',
      }, { merge: true });
      n += 1;
    });
    return n;
  });
}

/** Closes group `groupId`'s open removal requests about account `uid` (its
 *  new owner: nobody asks to remove the owner), and their notices on every
 *  member's, the asker's and the account's own. A request is about the
 *  account when it names its participant id (`you`) or is for a place it
 *  holds: that id (a creator's place), or a place its accepted invite joined
 *  (`usedBy`, not ended). makeOwner and the purge hand-over. */
export async function closeRequestsAbout(db, groupId, uid) {
  const ref = db.collection('groups').doc(groupId);
  const open = await ref.collection('requests').get();
  if (open.empty) return [];
  const profile = await db.collection('users').doc(uid).get();
  const you = profile.exists && typeof profile.data().participantId === 'string' ? profile.data().participantId : null;
  const joined = await db.collection('invites').where('groupId', '==', groupId).where('usedBy', '==', uid).get();
  const places = new Set([you, ...joined.docs.filter((d) => !d.data().endedAt).map((d) => d.data().memberId)].filter(Boolean));
  const about = open.docs.filter((d) => places.has(d.id) || (you !== null && d.data().you === you));
  if (!about.length) return [];
  const members = (await ref.get()).data()?.memberUids ?? [];
  await Promise.all(about.map(async (d) => {
    const id = groups.requestNoticeId(groupId, d.id);
    const uids = [...new Set([...members, d.data().askerUid, uid].filter((u) => typeof u === 'string' && u))];
    await Promise.all(uids.map((u) => db.collection('users').doc(u).collection('notices').doc(id).delete()));
    await d.ref.delete();
  }));
  return about.map((d) => d.id);
}

/** The daily purge (purgeDeletedAccounts): every account past its grace at
 *  `nowMs`, oldest first, a page of `pageSize` at a time, at most `maxPages`
 *  pages a run. One account that fails is logged and the rest are still
 *  purged; it is tried again on the next run. D-235 (L4): the run pages past
 *  the accounts that failed, so they never hold the next ones back; `more`
 *  says the run stopped at its bound with accounts still due (logged as a
 *  warning by the caller). */
export async function purgeDue(db, auth, nowMs = Date.now(), bucket = null, { pageSize = 50, maxPages = 10 } = {}) {
  const purged = [];
  const failed = [];
  let more = false;
  // D-254: the profiles past their grace, then the approvals documents that
  // carry the marker of an account with no profile (a pending or denied one,
  // index.js requestDeletion); each with its own bound of pages. An account
  // already taken in this run is not taken twice.
  for (const collection of ['users', 'approvals']) {
    const due = db.collection(collection).where('deleteAt', '<=', Timestamp.fromMillis(nowMs))
      .orderBy('deleteAt').limit(pageSize);
    let last = null;
    let done = false;
    for (let page = 0; page < maxPages; page += 1) {
      const dueNow = await (last ? due.startAfter(last) : due).get();
      for (const d of dueNow.docs) {
        if (!account.due(d.data(), nowMs) || purged.includes(d.id) || failed.includes(d.id)) continue;
        try {
          await purgeAccount(db, auth, d.id, bucket);
          purged.push(d.id);
        } catch (e) {
          logger.warn('purge: an account failed', { code: typeof e?.code === 'string' || typeof e?.code === 'number' ? e.code : 'other' });
          failed.push(d.id);
        }
      }
      if (dueNow.size < pageSize) { done = true; break; }
      last = dueNow.docs[dueNow.docs.length - 1];
    }
    if (!done) {
      const left = await (last ? due.startAfter(last) : due).limit(1).get();
      more = more || !left.empty;
    }
  }
  return { purged, failed, more };
}
