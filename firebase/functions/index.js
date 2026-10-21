// SKIPTI's Cloud Functions (ADR-0040; the Firebase plan's unit 5, D-195).
//
// Membership changes only here: the security rules let no client touch a
// group's `memberUids` or read an invite. A group invites a NAMED person (the
// person is already in the group's ledger); the link carries a random token,
// the cloud keeps only its SHA-256, and the link works once, for 7 days.
//
// Plain JavaScript, no build step. Every refusal has a test in
// firebase/tests/functions.test.mjs, run against the emulators by the gate.
import { createHash, randomBytes } from 'node:crypto';
import { initializeApp } from 'firebase-admin/app';
import { FieldPath, FieldValue, Timestamp, getFirestore } from 'firebase-admin/firestore';
import { getMessaging } from 'firebase-admin/messaging';
import { getAuth } from 'firebase-admin/auth';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { setGlobalOptions } from 'firebase-functions/v2';
import * as logger from 'firebase-functions/logger';
import * as push from './push.js';
import * as people from './people.js';
import * as account from './account.js';
import * as store from './account-store.js';
import * as groups from './groups.js';

initializeApp();
// D-197: a runtime identity of their own (Cloud Datastore User, Firebase Cloud
// Messaging Admin, Eventarc Event Receiver, Cloud Run Invoker), not the default
// compute account, which in this organization holds only the Cloud Build role
// (docs/CLOUD-SECURITY.md G-02). The emulators ignore it.
// D-235 (M1, round 2 I3): memory set, never the default 256 MiB, and at most
// CONCURRENCY calls an instance (firebase-tools' default at 512 MiB is 80).
// One log-reading call holds at most one page of records (OPS_PAGE = 50 of at
// most 192 KiB, the rules' cap: 9.4 MiB, about 19 MiB decoded) plus the
// compact events it keeps: at most OPS_BYTES_PER_AUTHOR = 512 KiB of
// estimated heap an author (groups.opBytes, checked BEFORE an op is kept, and
// at most 500 members an op), and the removals (at most 1000, about 0.3 MiB).
// An honest author keeps a few KB. A group of 60 authors who all flood: 19 +
// 30 + 0.3, about 50 MiB a call. Six such calls are 300 MiB, with Node's own
// ~80 MiB under 512 MiB.
export const CONCURRENCY = 6;
setGlobalOptions({
  region: 'us-central1', maxInstances: 10, memory: '512MiB', concurrency: CONCURRENCY,
  serviceAccount: 'skipti-functions@skipti-26.iam.gserviceaccount.com',
});
// Callables are reachable by anyone at the door (Cloud Run's invoker); each
// one checks the caller's Firebase sign-in itself (`signedIn`), and the tests
// prove every refusal. Declared so that every deploy sets it: D-195's first
// deploy failed at the build, and the update that followed never set it.
const CALLABLE = { invoker: 'public' };

const db = getFirestore();
export const INVITE_DAYS = 7;
const HEX32 = /^[0-9a-f]{32}$/;
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
// D-235 (L1): a Firebase Auth uid (28 letters and digits from Google and
// Apple sign-in); never a slash, so never a path into another document.
const UID = /^[A-Za-z0-9]{1,128}$/;
// D-237 (the Fable audit, A3): participant ids are not unique (a phone writes
// its profile's own), so "another account's participant id" (decision 47,
// G-29) is decided by who REGISTERED the id first: users/{uid}.createdAt is
// the server's time at the profile's creation, which only the rules write,
// and the id is written then and never changed. A profile made later with
// the same id (a copy: an impostor's, or the same phone's second account)
// denies the first account nothing, and its own marks and aliases with the
// id are refused; an id a caller never registered (one copied into its log)
// is another's as soon as any account registered it, as before. Before this,
// one copied profile stripped a person of every held-place mark (D-230), and
// the join went through unmarked. At most HOLDERS_MAX holders are read, in
// no particular order; a read that comes back full may have left the real
// registrant out, so it fails closed: the id is then another's for every
// caller, as the old rule read it (round 2, review I2).
const HOLDERS_MAX = 20;
const createdMillis = (d) => (typeof d.data()?.createdAt?.toMillis === 'function' ? d.data().createdAt.toMillis() : 0);
/** The accounts whose profile holds participant id `you`, earliest registration first. */
async function holdersOf(you) {
  const found = await db.collection('users').where('participantId', '==', you).limit(HOLDERS_MAX).get();
  return found.docs.sort((a, b) => createdMillis(a) - createdMillis(b));
}
/** Whether participant id `you` is another account's: some other profile
 *  holds it, unless the caller's OWN profile holds it and was registered
 *  before every other one. An id the caller only copied into its log (a
 *  `you` op, which a modified phone writes at will) is another's as soon as
 *  any account registered it. */
async function registeredBefore(uid, you) {
  const found = await holdersOf(you);
  if (found.length >= HOLDERS_MAX) return true;
  const holders = found.filter((d) => d.id !== uid);
  if (!holders.length) return false;
  const own = await db.collection('users').doc(uid).get();
  if (!own.exists || own.data().participantId !== you) return true;
  const mine = createdMillis(own);
  return holders.some((d) => createdMillis(d) < mine);
}
// D-235 (M1, round 2 C1): what one read of a group's log keeps of each
// author (opsOf): their EARLIEST OPS_PER_AUTHOR group operations by the
// server's time, and at most OPS_BYTES_PER_AUTHOR of compact ops (estimated).
// Never a refusal: past the bound an author's later operations are not read,
// which hides only facts that author wrote (an honest account writes a few
// dozen in a group). Removals (server-marked) are always read in full. An
// account's own log (yousOf) is read up to YOUS_MAX records, the same way.
export const OPS_PER_AUTHOR = 300;
export const OPS_BYTES_PER_AUTHOR = 524288; // round 3 (R2-2): an estimate of heap bytes (groups.opBytes), checked before keeping
export const OPS_PAGE = 50;
// D-235 round 3 (R2-1): the most removal-marked records of PLACEHOLDERS a
// group ever holds (round 4, R3-1: removeMember of a place no account holds,
// and adoptRemovals, count them on the group document, a field no phone
// writes; removing an account is never capped or counted, and those are
// bounded by the accounts that ever joined). opsOf reads the type-3 ones in full: 1000 at most, 20
// pages of 50, each record's blob at most 48 KiB (recordShape), so 2.4 MiB a
// page and about 2 s of reads, far inside the callable's 60 s; kept compact,
// about 0.3 MiB. An honest group reaches it after about 90 removals of
// someone with ten currencies, or 1000 of someone with none.
export const REMOVALS_MAX = 1000;
const REMOVALS_FULL = 'This group has had too many removals.';
export const YOUS_MAX = 2000;
const YOUS_PAGE = 50;

const hash = (token) => createHash('sha256').update(token, 'utf8').digest('hex');

/** D-204: one log line per push send: how many phones, delivered, failed
 *  and FCM's error codes (push.outcome); never a token, uid or text. */
function report(fn, sent, response) {
  const o = push.outcome(response);
  (o.failed ? logger.warn : logger.info)(`${fn}: push`, { phones: sent.length, ...o });
}

function text(value, max, field) {
  if (typeof value !== 'string') throw new HttpsError('invalid-argument', `${field} is missing`);
  const trimmed = value.trim();
  if (!trimmed || trimmed.length > max) throw new HttpsError('invalid-argument', `${field} is not valid`);
  return trimmed;
}

function signedIn(request) {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError('unauthenticated', 'Sign in first.');
  return uid;
}

function token(data) {
  const value = data?.token;
  if (typeof value !== 'string' || !TOKEN.test(value)) throw new HttpsError('invalid-argument', 'Not an invite.');
  return value;
}

/** A usable invite, or the reason it is not. */
async function openInvite(tokenValue) {
  const ref = db.collection('invites').doc(hash(tokenValue));
  const snapshot = await ref.get();
  if (!snapshot.exists) throw new HttpsError('not-found', 'This invite does not exist.');
  const invite = snapshot.data();
  if (invite.usedBy) throw new HttpsError('failed-precondition', 'This invite was already used.');
  if (invite.expiresAt.toMillis() <= Date.now()) throw new HttpsError('failed-precondition', 'This invite has expired.');
  return { ref, invite };
}

// A member invites a named person in the group. Returns the token, which
// only the link carries. D-218: a place a member already holds is refused
// (ALREADY_EXISTS) before anything is written; acceptInvite stays the guarantee.
export const createInvite = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const fields = inviteFields(request.data ?? {});
  await memberGroup(fields.groupId, uid);
  const groupName = await refuseHeld(fields.groupId, fields.memberId);
  // D-235 decision 121 (extended to links): the link names the inviter and
  // the group from the server's records, as deliverInvite does, so peekInvite
  // shows a joiner the server's names; the phone's are checked and unused.
  return makeInvite(people.invitation(fields, await ownName(uid), groupName).link, uid);
});

/** The invite's ids and names, checked (createInvite and inviteUser). */
function inviteFields(data) {
  const groupId = data.groupId;
  const memberId = data.memberId;
  if (typeof groupId !== 'string' || !HEX32.test(groupId)) throw new HttpsError('invalid-argument', 'groupId is not valid');
  if (typeof memberId !== 'string' || !HEX32.test(memberId)) throw new HttpsError('invalid-argument', 'memberId is not valid');
  return {
    groupId, memberId,
    memberName: text(data.memberName, 80, 'memberName'),
    groupName: text(data.groupName, 80, 'groupName'),
    inviterName: data.inviterName == null ? null : text(data.inviterName, 80, 'inviterName'),
  };
}

/** The group, when the caller is one of its members. */
async function memberGroup(groupId, uid) {
  const group = await db.collection('groups').doc(groupId).get();
  if (!group.exists || !(group.data().memberUids ?? []).includes(uid)) {
    throw new HttpsError('permission-denied', 'Only a member of this group can invite to it.');
  }
  return group;
}

/** D-218: an invite for a place a member already holds is refused,
 *  ALREADY_EXISTS (a status none of the three invite Functions returned
 *  before, so a phone can tell it from "already in this group"). Held means
 *  what acceptInvite's check reads (holderIn): the group's creator place
 *  while its creator is a member and no removal was ever written of it
 *  (D-227 decision 37, creatorHolder: read from the group's own log, no
 *  longer from participant ids), or an accepted, not ended invite of a
 *  current member (which wins over the creator path, decision 36), or two
 *  (contested: held too, never the CHANGED holderIn throws). Read in a
 *  read-only transaction: the group's log (records are create-only, their
 *  authorUid and createdAt set by the rules) and the place's invites, whose
 *  usedBy and endedAt only the Functions write. No phone's claim about a
 *  place is consulted. The place can still become held before the invite is
 *  accepted: acceptInvite remains the guarantee. D-230 decision 64 (A8, A9):
 *  a place EVER removed (everRemoved, decision 37's rule, even once a phone
 *  adds the id back) is refused too, failed-precondition with CHANGED: a
 *  removed place never changes hands, so no earlier holder's mark can
 *  outrank a later one (the shells re-invite someone removed to a new place,
 *  D-210; core mints every new member id, so no honest flow re-adds a
 *  removed one). acceptInvite refuses an older link to it the same way. */
async function refuseHeld(groupId, member) {
  return db.runTransaction(async (tx) => {
    const ref = db.collection('groups').doc(groupId);
    const group = await again(tx, ref);
    const events = await opsOf(tx, ref, group);
    const log = await logOf(tx, ref, group, member, events);
    const own = creatorHolder(group, log, member);
    const links = await tx.get(db.collection('invites').where('groupId', '==', groupId).where('memberId', '==', member));
    const invited = groups.joinedAs(links.docs.map((d) => d.data()), group.memberUids);
    if (groups.placeHeld(own ? [own] : [], invited)) throw new HttpsError('already-exists', HELD);
    if (log.everRemoved) throw new HttpsError('failed-precondition', CHANGED);
    // D-235 decision 121: the group's name as the server reads it from the
    // same log (groupNameOf), for inviteUser's and inviteKnown's delivery.
    return groups.groupNameOf(events, group.name);
  }, { readOnly: true });
}

/** A link invite: a random token only the link carries; its hash at rest. */
async function makeInvite(fields, uid) {
  const tokenValue = randomBytes(32).toString('base64url');
  const expiresAt = Timestamp.fromMillis(Date.now() + INVITE_DAYS * 24 * 3600 * 1000);
  await db.collection('invites').doc(hash(tokenValue)).create({
    ...fields, createdBy: uid, createdAt: FieldValue.serverTimestamp(), expiresAt, usedBy: null,
  });
  return { token: tokenValue, expiresAt: expiresAt.toMillis() };
}

// What the invite is, before joining: who asked, which group, as whom.
export const peekInvite = onCall(CALLABLE, async (request) => {
  signedIn(request);
  const { invite } = await openInvite(token(request.data));
  return {
    groupName: invite.groupName, memberName: invite.memberName, inviterName: invite.inviterName ?? null,
    expiresAt: invite.expiresAt.toMillis(),
    // D-230 decision 57: the ids the phone's held place names, built before
    // it joins (acceptInvite's `records`). The link's holder learns both on
    // joining anyway: nothing new is exposed.
    groupId: invite.groupId, memberId: invite.memberId,
  };
});

// D-229/D-230: the one refusal shareAliases and sharePlaces skip a record
// with, and acceptInvite's held place when it is not written.
const NOT_YOURS = 'Not yours to share.';

// Joins: adds the caller to the group's members and spends the invite, in
// one transaction. The caller then claims the named person in the group's
// own log (a group record), which the rules now let them write. D-230: the
// phone may pass `records`, heldPlace ops it built: at least one for this
// place (one per id of the account, A5), and one per removed place of this group it held before (A1: a
// rejoin carries its old places, so core never voids their claims); each
// written marked in the same transaction when its checks hold (decision 56).
// Malformed `records` (not 1 to 11 well formed heldPlace ops, a (group,
// member, you) twice, another group, none for this place) refuse
// the call before anything is written, the invite unspent; the singular
// `record` of the first D-230 draft is refused the same way (nothing shipped
// it). A well formed record that fails a check is skipped and logged.
export const acceptInvite = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const tokenValue = token(request.data);
  if (request.data?.record != null) throw new HttpsError('invalid-argument', 'records are not valid');
  const records = request.data?.records ?? null;
  if (records !== null && groups.checkHeldRecords(records, groups.MAX_JOIN_HELD)) {
    throw new HttpsError('invalid-argument', 'records are not valid');
  }
  const ref = db.collection('invites').doc(hash(tokenValue));
  return db.runTransaction(async (tx) => {
    const snapshot = await tx.get(ref);
    if (!snapshot.exists) throw new HttpsError('not-found', 'This invite does not exist.');
    const invite = snapshot.data();
    if (invite.usedBy) throw new HttpsError('failed-precondition', 'This invite was already used.');
    if (invite.expiresAt.toMillis() <= Date.now()) {
      throw new HttpsError('failed-precondition', 'This invite has expired.');
    }
    const named = (records ?? []).map((r) => groups.json(r.payload.blob));
    if (records !== null && (named.some((a) => a.group !== invite.groupId)
      || !named.some((a) => a.member === invite.memberId))) {
      throw new HttpsError('invalid-argument', 'records are not valid');
    }
    const groupRef = db.collection('groups').doc(invite.groupId);
    const group = await tx.get(groupRef);
    if (!group.exists) throw new HttpsError('not-found', 'This group no longer exists.');
    // D-235 (H1): someone removed never comes back through a link made at or
    // before their removal (one they made themselves while a member, or an
    // older one forwarded to them): refused, the invite unspent, `former`
    // kept. A manager's link made after the removal still brings them back
    // (decision 49). A former entry without a readable time refuses every link.
    const removedAt = group.data().former?.[uid]?.at;
    if (group.data().former?.[uid] !== undefined && (typeof removedAt?.toMillis !== 'function'
      || typeof invite.createdAt?.toMillis !== 'function' || invite.createdAt.toMillis() <= removedAt.toMillis())) {
      throw new HttpsError('failed-precondition', REMOVED);
    }
    // D-210: a place is one account's. Any member can make an invite for any
    // place, so one held by another member (its creator, or whoever's
    // accepted invite joined it) is never joined a second time: whose place
    // it is stays the server's record, never a contest a member can stage.
    // D-227: the joiner's own participant id is not checked any more: no
    // place resolves through participant ids, so whatever id it carries makes
    // it no place but the one this invite names.
    const held = await holderIn(tx, groupRef, group.data(), invite.memberId);
    if (held.uid && held.uid !== uid) throw new HttpsError('failed-precondition', HELD);
    // D-230 decision 64: an older link to a place ever removed is refused,
    // the invite unspent (refuseHeld refuses making one).
    if (held.log.everRemoved) throw new HttpsError('failed-precondition', CHANGED);
    // D-235 (H1 hygiene): a place the group's log never made is nobody's to
    // join (a link minted for any 32-hex id); refused, the invite unspent. At
    // the join, not when the link is made: a phone makes the link right after
    // adding the person, before its push has put the addMember up.
    if (!held.log.place) throw new HttpsError('failed-precondition', NOT_YET);
    // D-229 decision 52: an account that already holds a place in this group
    // never joins a second one: core reads two present claims naming one id
    // as contested (decision 44), so its history would read as unknown and
    // its own phones would turn the group read-only. Refused before any
    // write, the invite unspent; `details.member` tells the phones to say so.
    // Another invite for the place it already holds stays allowed (D-210 fix
    // 4), and a removed account holds no place (its invites ended, the
    // creator place ever removed), so a rejoin (decision 49) is not refused.
    const mine = await placesOf(tx, groupRef, group.data(), uid, held.log.events);
    if (mine.some((m) => m !== invite.memberId)) {
      throw new HttpsError('already-exists', MEMBER, { member: true });
    }
    // D-230 decision 66: an id another account's mark already names, on a
    // member still present, is that account's in this group: a join that
    // sends it is refused whole, the invite unspent (a copied id is a
    // denial, never a takeover; removing the copier's member lifts it).
    if (records !== null) {
      const marked = await heldMarks(tx, groupRef);
      for (const a of named) {
        if (await namedByAnother(tx, groupRef, group.data(), uid, a.you, marked, held.log.events)) {
          throw new HttpsError('failed-precondition', HELD);
        }
      }
    }
    // D-230 decision 56 and A1: the built heldPlace records, each written
    // marked in this transaction when its checks hold; one that fails a check
    // never fails the join (sharePlaces marks it at the next push). The
    // invite's place: the joiner holds it by this invite, never as creator.
    // Any other: a removed place of this group the joiner held before
    // (heldBefore). Then the checks sharePlaces makes. Every read here
    // precedes the writes.
    const marks = [];
    if (records !== null) {
      const g = group.data();
      const events = held.log.events;
      const logs = new Map();
      const yous = (u) => {
        if (!logs.has(u)) logs.set(u, yousOf(u));
        return logs.get(u);
      };
      for (const [i, r] of records.entries()) {
        const a = named[i];
        try {
          const refuse = () => new HttpsError('permission-denied', NOT_YOURS);
          let asCreator = false;
          if (a.member !== invite.memberId) {
            const log = await logOf(tx, groupRef, g, a.member, events);
            const before = log.place ? await heldBefore(tx, groupRef, g, a.member, uid, log) : null;
            if (!before) throw refuse();
            asCreator = before === 'creator';
          }
          if (await rawYouRefused(tx, groupRef, g, a, uid, asCreator, events)) throw refuse();
          await checkHeldId(r, a, uid, yous, refuse);
          const mark = await heldMarkDoc(tx, groupRef, g, r, a, uid, yous, refuse, events, i);
          if (mark) marks.push([mark, r]);
        } catch (e) {
          if (!(e instanceof HttpsError)) throw e;
          logger.warn('acceptInvite: a held place was not written', { code: e.code });
        }
      }
    }
    // D-208: and the in-app copy of this invite leaves your invitations, so
    // none of your phones keeps a card whose Join can only fail.
    const copies = await tx.get(db.collection('users').doc(uid).collection('invites').where('token', '==', tokenValue));
    // D-210: someone removed and invited again is a member again, no longer
    // a former one.
    tx.update(groupRef, 'memberUids', FieldValue.arrayUnion(uid), new FieldPath('former', uid), FieldValue.delete());
    tx.update(ref, { usedBy: uid, usedAt: FieldValue.serverTimestamp() });
    for (const [mark, r] of marks) tx.create(mark.doc, heldDoc(r, uid, FieldValue.serverTimestamp(), mark.hlc));
    copies.docs.forEach((d) => tx.delete(d.ref));
    return { groupId: invite.groupId, memberId: invite.memberId, groupName: invite.groupName };
  });
});

// D-196 (unit 6): a group record with a push line tells the group's other
// members' phones. Every decision is in push.js (tested directly); this only
// reads the group and the members' phones, sends, and forgets the push
// addresses FCM says are gone. A record without a notice (people, names,
// settlements, any record from before D-196) sends nothing.
export const onGroupRecord = onDocumentCreated('groups/{groupId}/records/{recordId}', async (event) => {
  const record = event.data?.data();
  const n = push.notice(record);
  if (!n) return;
  const { groupId } = event.params;
  const group = await db.collection('groups').doc(groupId).get();
  if (!group.exists) return;
  const uids = push.recipients(group.data(), record.authorUid);
  if (!uids.length) return;
  const phones = (await Promise.all(uids.map(async (uid) => {
    const list = await db.collection('users').doc(uid).collection('devices').get();
    return list.docs.map((d) => ({ uid, id: d.id, data: d.data() }));
  }))).flat();
  const sent = push.tokens(phones);
  if (!sent.length) return;
  const response = await getMessaging().sendEachForMulticast({
    ...push.message(n, groupId, group.data().name), tokens: sent.map((s) => s.token),
  });
  report('onGroupRecord', sent, response);
  await Promise.all(push.deadTokens(sent, response).map((s) =>
    db.collection('users').doc(s.uid).collection('devices').doc(s.id)
      .update({ fcmToken: FieldValue.delete() }).catch(() => {})));
});

// ---- D-197 (unit 7): finding people. Every rule is in people.js (tested
// directly); these read and write the documents. ----

/** Charges today's counter for `kind`, or refuses past the daily limit. */
async function charge(uid, kind, amount = 1) {
  const ref = db.collection('limits').doc(uid);
  await db.runTransaction(async (tx) => {
    const snapshot = await tx.get(ref);
    const next = people.charge(snapshot.exists ? snapshot.data() : null, people.utcDay(Date.now()), kind, amount);
    if (!next) throw new HttpsError('resource-exhausted', 'Too many for today. Try again tomorrow.');
    tx.set(ref, next);
  });
}

// Your @username. Unique by the document id of usernames/{handle}. C2: set
// once free, changed once (usernameChanged, which only Functions write),
// never removed; the old handle is free at once; never before the profile
// is complete (its participantId is written by the phone's registration).
// `first: true` is the step's save, which never spends the change.
export const setUsername = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const raw = request.data?.username;
  const wanted = raw == null || raw === '' ? null : people.username(raw);
  if (wanted?.error) throw new HttpsError('invalid-argument', 'username ' + wanted.error);
  const userRef = db.collection('users').doc(uid);
  return db.runTransaction(async (tx) => {
    const user = await tx.get(userRef);
    const data = user.exists ? user.data() : {};
    if (typeof data.participantId !== 'string') {
      throw new HttpsError('failed-precondition', 'Your account is still being set up. Try again.');
    }
    const old = data.username ?? null;
    const changed = data.usernameChanged === true;
    const handle = wanted?.handle ?? null;
    const step = people.usernameStep(old, handle, changed, request.data?.first === true);
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

const SETTING_UP = 'Your account is still being set up. Try again.';

/** D-235 (L3): writes `fields` into the account's profile only once the
 *  phone's registration has made it, in a transaction. A Function never
 *  creates a profile: the phone's registration only updates one that exists
 *  and can never add the participant id (the rules), so a profile a Function
 *  made first would stay without it for good. Returns whether a profile was
 *  there; refuses (failed-precondition) when not, unless `refuse` is false. */
async function profileUpdate(uid, fields, refuse = true) {
  const ref = db.collection('users').doc(uid);
  return db.runTransaction(async (tx) => {
    const profile = await tx.get(ref);
    if (!profile.exists) {
      if (refuse) throw new HttpsError('failed-precondition', SETTING_UP);
      return false;
    }
    tx.update(ref, fields);
    return true;
  });
}

// Whether people can find you; the email fingerprint is kept only while you
// are findable, and only for your VERIFIED sign-in email (never the app's word).
export const setFindable = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const on = request.data?.findable;
  if (typeof on !== 'boolean') throw new HttpsError('invalid-argument', 'findable is not a boolean');
  const fingerprint = people.profileHash(on, request.auth.token);
  await profileUpdate(uid, { findable: on, emailHash: fingerprint ?? FieldValue.delete() });
  return { findable: on, byEmail: fingerprint != null };
});

// One username, or up to 500 email fingerprints; only findable people, never
// the caller. Nothing it is sent is stored.
export const findPeople = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const asked = people.search(request.data);
  if (asked.error) throw new HttpsError('invalid-argument', asked.error);
  await charge(uid, 'searches');
  const found = [];
  if (asked.username) {
    const owner = await db.collection('usernames').doc(asked.username).get();
    const theirs = owner.exists ? owner.data().uid : null;
    if (theirs && theirs !== uid) {
      const profile = await db.collection('users').doc(theirs).get();
      if (profile.exists && people.findable(profile.data())) found.push(people.person(theirs, profile.data()));
    }
    return { people: found };
  }
  await charge(uid, 'hashes', asked.emailHashes.length);
  for (let i = 0; i < asked.emailHashes.length; i += 30) {
    const chunk = asked.emailHashes.slice(i, i + 30);
    const hits = await db.collection('users').where('emailHash', 'in', chunk).get();
    for (const d of hits.docs) {
      if (d.id === uid || d.data().findable !== true) continue;
      found.push(people.person(d.id, d.data(), d.data().emailHash));
    }
  }
  return { people: found };
});

// Invites someone found in the app to be a named person of your group: the
// same link invite as createInvite, delivered to their account (and a push).
// D-218: a place a member already holds is refused (ALREADY_EXISTS), after
// the target checks and before the daily quota is charged.
export const inviteUser = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const fields = inviteFields(request.data ?? {});
  const target = request.data?.targetUid;
  if (typeof target !== 'string' || !UID.test(target) || target === uid) {
    throw new HttpsError('invalid-argument', 'targetUid is not valid');
  }
  const group = await memberGroup(fields.groupId, uid);
  if ((group.data().memberUids ?? []).includes(target)) {
    throw new HttpsError('failed-precondition', 'They are already in this group.');
  }
  const profile = await db.collection('users').doc(target).get();
  if (!profile.exists || !people.findable(profile.data())) throw new HttpsError('not-found', 'No such person.');
  const groupName = await refuseHeld(fields.groupId, fields.memberId);
  await charge(uid, 'invites');
  await deliverInvite('inviteUser', fields, uid, target, groupName);
  return { invited: true };
});

/** An in-app invite to `target`: the link invite (makeInvite), a copy in
 *  their account, and a push to their phones. inviteUser and inviteKnown.
 *  D-235 decision 121 (the founder; Firebase audit M2): all three name the
 *  inviter and the group from the server's records only: the inviter's name
 *  from their profile (ownName; none is null, which the push shows as
 *  "Someone" and the apps as "You are invited to …"), the group's from its
 *  log (`groupName`, refuseHeld's groupNameOf). The phone's inviterName and
 *  groupName are checked as before (old phones send them) and never used:
 *  people.invitation builds every copy from the server's names alone. */
async function deliverInvite(fn, fields, uid, target, groupName) {
  const inviterName = await ownName(uid);
  const named = people.invitation(fields, inviterName, groupName);
  // D-210: whose account holds the copy, so deleteGroup can end it.
  const { token: tokenValue, expiresAt } = await makeInvite({ ...named.link, deliveredTo: target }, uid);
  await db.collection('users').doc(target).collection('invites').add({
    ...named.copy, token: tokenValue,
    createdAt: FieldValue.serverTimestamp(), expiresAt: Timestamp.fromMillis(expiresAt),
  });
  const phones = await db.collection('users').doc(target).collection('devices').get();
  const sent = push.tokens(phones.docs.map((d) => ({ uid: target, id: d.id, data: d.data() })));
  if (!sent.length) return;
  const response = await getMessaging().sendEachForMulticast({
    ...named.push, tokens: sent.map((s) => s.token),
  }).catch((e) => {
    logger.warn(`${fn}: push threw`, { code: typeof e?.code === 'string' ? e.code : 'other' });
    return null;
  });
  if (!response) return;
  report(fn, sent, response);
  await Promise.all(push.deadTokens(sent, response).map((s) =>
    db.collection('users').doc(s.uid).collection('devices').doc(s.id)
      .update({ fcmToken: FieldValue.delete() }).catch(() => {})));
}

// D-207 (the founder, 2026-10-13): invites someone you already share a group
// with, named by their participant id (the phone knows it from that group's
// claims; it never holds their uid). Allowed only when the caller AND the
// target are members of `viaGroupId`; being findable does not matter there.
// D-218: a place a member already holds is refused (ALREADY_EXISTS), after
// the target checks and before the daily quota is charged.
export const inviteKnown = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const fields = inviteFields(request.data ?? {});
  const viaGroupId = request.data?.viaGroupId;
  const participantId = request.data?.participantId;
  if (typeof viaGroupId !== 'string' || !HEX32.test(viaGroupId)) throw new HttpsError('invalid-argument', 'viaGroupId is not valid');
  if (typeof participantId !== 'string' || !HEX32.test(participantId)) {
    throw new HttpsError('invalid-argument', 'participantId is not valid');
  }
  const via = await db.collection('groups').doc(viaGroupId).get();
  const viaMembers = via.exists ? via.data().memberUids ?? [] : [];
  if (!viaMembers.includes(uid)) throw new HttpsError('permission-denied', 'Only someone in that group can invite from it.');
  const group = await memberGroup(fields.groupId, uid);
  // D-237 (A3, G-29): the member of the via group who registered the id first; an outsider's copy of a member's
  // id (which used to make that member un-invitable: NOT_FOUND on two holders) changes nothing.
  const found = await holdersOf(participantId);
  const holder = found.length >= HOLDERS_MAX ? null : found.find((d) => viaMembers.includes(d.id));
  if (!holder) throw new HttpsError('not-found', 'No such person.');
  const target = holder.id;
  if (target === uid) throw new HttpsError('invalid-argument', 'That is you.');
  if ((group.data().memberUids ?? []).includes(target)) {
    throw new HttpsError('failed-precondition', 'They are already in this group.');
  }
  const groupName = await refuseHeld(fields.groupId, fields.memberId);
  await charge(uid, 'invites');
  await deliverInvite('inviteKnown', fields, uid, target, groupName);
  return { invited: true };
});

// ---- D-198 (unit 8): your phones and deleting your account. Rules in
// account.js, the deleting in account-store.js (both tested). ----

// A phone signed in to the account: its other phones are asked "Was this you?".
export const onDeviceCreated = onDocumentCreated('users/{uid}/devices/{deviceId}', async (event) => {
  const { uid, deviceId } = event.params;
  const phones = (await db.collection('users').doc(uid).collection('devices').get()).docs
    .map((d) => ({ id: d.id, data: d.data() }));
  const sent = account.otherPhones(phones, deviceId);
  if (!sent.length) return;
  const response = await getMessaging().sendEachForMulticast({
    ...account.devicePush(event.data?.data()?.name), tokens: sent.map((s) => s.token),
  });
  report('onDeviceCreated', sent, response);
  await Promise.all(push.deadTokens(sent, response).map((s) =>
    db.collection('users').doc(uid).collection('devices').doc(s.id)
      .update({ fcmToken: FieldValue.delete() }).catch(() => {})));
});

// Signs one of your phones out: it signs itself out when it next reaches the
// cloud (Firebase cannot end another phone's session by itself).
export const signOutDevice = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const id = request.data?.deviceId;
  if (!account.deviceId(id)) throw new HttpsError('invalid-argument', 'deviceId is not valid');
  if (!(await store.signOutPhones(db, uid, id))) throw new HttpsError('not-found', 'No such phone.');
  return { signedOut: 1 };
});

// Every phone, this one too: each is flagged, and every refresh token of the
// account is revoked, so no phone stays signed in past its current hour.
export const signOutEverywhere = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const n = await store.signOutPhones(db, uid);
  await getAuth().revokeRefreshTokens(uid);
  return { signedOut: n };
});

// SPEC §4.4: deletion in 7 days unless you sign in and keep the account;
// every phone is signed out now.
export const requestDeletion = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const at = account.deletionAt(Date.now());
  await profileUpdate(uid, { deleteAt: Timestamp.fromMillis(at), deletionRequestedAt: FieldValue.serverTimestamp() });
  await store.signOutPhones(db, uid);
  await getAuth().revokeRefreshTokens(uid);
  return { deleteAt: at };
});

export const cancelDeletion = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  // No profile: nothing was asked, nothing to cancel.
  await profileUpdate(uid, { deleteAt: FieldValue.delete(), deletionRequestedAt: FieldValue.delete() }, false);
  return { cancelled: true };
});

// Once a day: every account past its grace is deleted for good.
export const purgeDeletedAccounts = onSchedule({ schedule: 'every 24 hours', timeoutSeconds: 1800 }, async () => {
  // D-210: one account that fails is logged and the others still go
  // (account-store.js purgeDue); it is tried again tomorrow. D-235 (L4): a
  // run that stops at its bound with accounts still due says so as a warning.
  const { purged, failed, more } = await store.purgeDue(db, getAuth(), Date.now());
  (more ? logger.warn : logger.info)('purge: done', { purged: purged.length, failed: failed.length, more });
});

// ---- D-210 (C3): roles, removal requests, removing someone, deleting a
// group. Every decision is in groups.js (tested directly); these read the
// documents, ask it, write in a transaction that reads the group again, and
// tell the people concerned (notices, and a push). ----

const ROLE_REFUSED = 'Only the owner or an admin can do that.';
const CHANGED = 'That changed on another phone. Try again.';
// D-218: acceptInvite's refusal (failed-precondition) and the invite Functions' (already-exists).
const HELD = 'Someone has already joined as this person.';
// D-229 decision 52: acceptInvite's refusal of an account already in the group
// (ALREADY_EXISTS with details { member: true }); the phones show these words.
const MEMBER = 'You\'re already in this group.';
// D-235 (H1): acceptInvite's refusal of a link made before the caller's removal.
const REMOVED = 'This invite was made before you were removed. Ask for a new one.';
// D-235: acceptInvite's refusal of a place not in the group's log yet.
const NOT_YET = 'This person is not in the group yet. Try again in a moment.';

function hex(value, field) {
  if (typeof value !== 'string' || !HEX32.test(value)) throw new HttpsError('invalid-argument', `${field} is not valid`);
  return value;
}

/** The group, when the caller is one of its members. */
async function groupOf(groupId, uid) {
  const snapshot = await db.collection('groups').doc(groupId).get();
  if (!snapshot.exists) throw new HttpsError('not-found', 'This group no longer exists.');
  const group = snapshot.data();
  if (!(group.memberUids ?? []).includes(uid)) {
    throw new HttpsError('permission-denied', 'Only a member of this group can do that.');
  }
  return { ref: snapshot.ref, group };
}

/** The group again, inside a transaction: gone is not-found. */
async function again(tx, ref) {
  const snapshot = await tx.get(ref);
  if (!snapshot.exists) throw new HttpsError('not-found', 'This group no longer exists.');
  return snapshot.data();
}

// D-210: who is who in a group. D-227: a participant id decides no place. It
// is not unique across accounts (an account's first phone picks it when the
// account is made), and one account on two phones may have made a group
// under the other phone's "you". A place is held by the member whose accepted
// invite joined it, else by the group's creator when it is the creator place
// (the first member of the creator's own earliest create, read from the
// group's log) and no removal was ever written of it (decision 36: the invite
// wins; decision 37). A participant id is read only as such: what a phone sends
// as `you`, a request's stored `you`, and the D-210 role answers.

async function participantOf(uid) {
  const d = await db.collection('users').doc(uid).get();
  return d.exists && typeof d.data().participantId === 'string' ? d.data().participantId : null;
}

/** Account `uid`'s own participant id, read in `tx`, or null. */
async function participantIn(tx, uid) {
  const d = await tx.get(db.collection('users').doc(uid));
  return d.exists && typeof d.data().participantId === 'string' ? d.data().participantId : null;
}

/** One stored record as an event of the group `groupId`'s log, or null: a
 *  type-3 JSON op of this group, kept compact (groups.compactOp). */
function eventOf(d, groupId) {
  const r = d.data();
  if (r.type !== '3') return null;
  let op = null;
  try { op = JSON.parse(Buffer.from(r.payload?.blob ?? []).toString('utf8')); } catch { return null; }
  if (op?.group !== groupId) return null;
  const at = typeof r.createdAt?.toMillis === 'function' ? r.createdAt.toMillis() : 0;
  const h = r.hlc ?? {};
  return {
    createdAt: at, removal: r.removal === true, authorUid: r.authorUid,
    hlc: { millis: h.millis, counter: h.counter, device: h.device }, blob: groups.compactOp(op),
  };
}

/** Every account that can have written into `group`'s log: its members, its
 *  former members, its creator and (D-235) the accounts the purge took out
 *  (`departed`, written only by Functions). */
function authorsOf(group, more = []) {
  const all = [...(group.memberUids ?? []), ...Object.keys(group.former ?? {}), group.createdBy, ...(group.departed ?? []), ...more];
  return [...new Set(all.filter((u) => typeof u === 'string' && u))].sort();
}

/** The group's log, read in `tx` so a record written meanwhile is seen: the
 *  type-3 records that are JSON ops of this group, as {createdAt (the server's
 *  millis), removal, authorUid, hlc, blob (compact)}. Records are create-only
 *  and the rules set authorUid and createdAt; `removal` only removeMember and
 *  adoptRemovals write. D-235 round 2 (C1, I3): read per author, oldest first
 *  by the server's time (a phone cannot backdate createdAt, so what an author
 *  wrote before a flood always stays), a page of OPS_PAGE at a time, keeping
 *  at most OPS_PER_AUTHOR records and OPS_BYTES_PER_AUTHOR (estimated) an
 *  author; never refused. Removal-marked records are read in full by their
 *  own query (only Functions write the mark). Needs the composite index
 *  records (type, authorUid, createdAt) in firestore.indexes.json. */
async function opsOf(tx, ref, group) {
  const byId = new Map();
  // Removal-marked records first, in full (at most REMOVALS_MAX, counted by
  // the Functions that write them), type 3 only.
  const removals = ref.collection('records').where('removal', '==', true).where('type', '==', '3')
    .orderBy(FieldPath.documentId()).limit(OPS_PAGE);
  const removers = new Set();
  let last = null;
  for (;;) {
    const page = await tx.get(last ? removals.startAfter(last) : removals);
    for (const d of page.docs) {
      const e = eventOf(d, ref.id);
      if (e) byId.set(d.id, e);
      if (typeof d.data().authorUid === 'string') removers.add(d.data().authorUid);
    }
    if (page.size < OPS_PAGE) break;
    last = page.docs[page.docs.length - 1];
  }
  // Decision 113: an account purged before `departed` existed is still an
  // author when a used invite of the group names it as its maker, or a
  // removal it wrote is in the log (no production backfill).
  const used = await tx.get(db.collection('invites').where('groupId', '==', ref.id).where('usedBy', '>', '').select('createdBy'));
  const makers = used.docs.map((d) => d.data().createdBy);
  const base = ref.collection('records').where('type', '==', '3');
  for (const author of authorsOf(group, [...removers, ...makers])) {
    const query = base.where('authorUid', '==', author).orderBy('createdAt').limit(OPS_PAGE);
    let kept = 0;
    let bytes = 0;
    let cut = false;
    let after = null;
    for (;;) {
      const page = await tx.get(after ? query.startAfter(after) : query);
      for (const d of page.docs) {
        if (kept >= OPS_PER_AUTHOR) { cut = true; break; }
        kept += 1;
        if (byId.has(d.id)) continue;
        const e = eventOf(d, ref.id);
        if (!e) continue;
        // Round 3 (R2-2): an op that would take its author past the budget is
        // not kept, and nothing later of that author is read.
        const b = groups.opBytes(e.blob);
        if (bytes + b > OPS_BYTES_PER_AUTHOR) { cut = true; break; }
        bytes += b;
        byId.set(d.id, e);
      }
      if (cut || page.size < OPS_PAGE) break;
      after = page.docs[page.docs.length - 1];
    }
    if (cut) logger.warn('opsOf: an author\'s later operations were not read', { kept });
  }
  return [...byId.values()];
}

/** What the group's log says of place `member` (`events` from opsOf, read
 *  here when not given): whether a create or addMember op named it (`place`);
 *  whether removeMember removed it and nobody added it back since (`removed`;
 *  groups.removedSince compares the server's createdAt, never a phone's
 *  clock); the creator's place (`creatorPlace`, groups.creatorPlaceOf); and
 *  every claim op as {author, member, you}. A claim decides no holder (any
 *  member can write one about any place); placeIn reads the holder's own. */
async function logOf(tx, ref, group, member, events = null) {
  const all = events ?? await opsOf(tx, ref, group);
  let place = false;
  const claims = [];
  for (const e of all) {
    const op = e.blob;
    if (op.op === 'create' && Array.isArray(op.members) && op.members.some((m) => m?.id === member)) place = true;
    if (op.op === 'addMember' && op.member?.id === member) place = true;
    if (op.op === 'claim') claims.push({ author: e.authorUid, member: op.member, you: op.you });
  }
  // D-227 decision 37: a removal ever written of the place (only removeMember
  // and adoptRemovals write `removal: true`), whatever was added back since.
  const everRemoved = all.some((e) => e.removal === true && e.blob.op === 'removeMember' && e.blob.member === member);
  return {
    place, removed: groups.removedSince(all, member), everRemoved,
    creatorPlace: groups.creatorPlaceOf(all, group.createdBy), events: all, claims,
  };
}

/** D-227: the account that holds place `member` as the group's creator, or
 *  null: its creator place (`log` from logOf for that place) while the creator
 *  is a member and no removal was ever written of the place (decision 37: a
 *  creator removed from its creator place who comes back holds through an
 *  invite like anyone else, and an addMember of the old place by any phone
 *  gives it nothing; one removed through another place it joined by invite
 *  holds its creator place again when it rejoins, unless an accepted invite
 *  holds it by then, decision 36: harmless, residue in CLOUD-SECURITY).
 *  `createdBy` never changes (firestore.rules), and only the creator's own
 *  creates count. An accepted invite still wins over it (holderIn). */
function creatorHolder(group, log, member) {
  const by = group.createdBy;
  if (typeof by !== 'string' || member !== log.creatorPlace || log.everRemoved) return null;
  return (group.memberUids ?? []).includes(by) ? by : null;
}

/** The member account that holds place `member` of the group at `ref`, read
 *  in `tx`: the member whose accepted invite joined it (invites/{hash}.usedBy,
 *  set by acceptInvite alone; groups.joinedAs), else its creator
 *  (creatorHolder), else null (nobody with an account here). Two members
 *  joined by invite is refused (CHANGED). D-227 decision 36: when the creator
 *  path and another member's invite name one place, the invite wins and the
 *  creator holds it not: the invite is the server's record, the create op a
 *  client's, and nothing forces the creator's first create to land before
 *  anyone joins (a create written late must not make a member unremovable).
 *  `events` (opsOf) saves a second read of the log. Returns whether the holder
 *  holds it as creator, the place's invites and the log (logOf). */
async function holderIn(tx, ref, group, member, events = null) {
  const log = await logOf(tx, ref, group, member, events);
  const own = creatorHolder(group, log, member);
  const links = await tx.get(db.collection('invites').where('groupId', '==', ref.id).where('memberId', '==', member));
  const invited = groups.joinedAs(links.docs.map((d) => d.data()), group.memberUids);
  if (invited.contested) throw new HttpsError('failed-precondition', CHANGED);
  const uid = invited.uid ?? own;
  return { uid, creator: own !== null && uid === own, links: links.docs, log };
}

/** Whose place `member` is (holderIn), and what the log says of it (logOf),
 *  read in `tx`. `you`, when the phone sends it, must be the holder's own word
 *  for itself (D-227): its participant id, the `you` of a claim op on this
 *  place that the holder's account wrote, or the place itself when it holds it
 *  as creator (the creator place is its phone's "you" at creation). `you` only
 *  checks what the phone named; holderIn decides the holder. Returns the
 *  holder's participant id as `person`. */
async function placeIn(tx, ref, group, member, you) {
  const { uid: target, creator, links, log } = await holderIn(tx, ref, group, member);
  const person = target ? await participantIn(tx, target) : null;
  if (you != null) {
    const own = target && (you === person
      || log.claims.some((c) => c.author === target && c.member === member && c.you === you)
      || (creator && you === member));
    if (!own) throw new HttpsError('not-found', 'No such person.');
  }
  return { place: log.place, removed: log.removed, target, person, links, events: log.events };
}

/** D-227: the places account `uid` holds in the group at `ref`, read in `tx`,
 *  sorted: its creator place, and the place of every invite it used that has
 *  not ended, each kept only when holderIn resolves it to `uid`. A contested
 *  place is left out, never an error. Empty while the creator's create op has
 *  not reached the cloud. `events` from opsOf. */
async function placesOf(tx, ref, group, uid, events) {
  if (typeof uid !== 'string' || !uid) return [];
  const candidates = new Set();
  const made = groups.creatorPlaceOf(events, group.createdBy);
  if (made && uid === group.createdBy) candidates.add(made);
  const used = await tx.get(db.collection('invites').where('groupId', '==', ref.id).where('usedBy', '==', uid));
  for (const d of used.docs) {
    const i = d.data();
    if (!i.endedAt && typeof i.memberId === 'string' && HEX32.test(i.memberId)) candidates.add(i.memberId);
  }
  const held = [];
  for (const member of [...candidates].sort()) {
    try {
      if ((await holderIn(tx, ref, group, member, events)).uid === uid) held.push(member);
    } catch (e) {
      if (!(e instanceof HttpsError)) throw e;
    }
  }
  return held;
}

/** The name account `uid` gives itself (users/{uid}.name), which notices
 *  carry as who did it: the server's, never a name the phone sends (D-210
 *  final review). None is "Someone". */
async function nameOf(uid) {
  return (await ownName(uid)) ?? 'Someone';
}

/** The name account `uid` set on its profile (users/{uid}.name), trimmed, at
 *  most 80 characters; null when it set none (D-235 decision 121). */
async function ownName(uid) {
  const d = await db.collection('users').doc(uid).get();
  const name = d.exists ? d.data().name : null;
  return typeof name === 'string' && name.trim() ? name.trim().slice(0, 80) : null;
}

/** A checked phone-built record (groups.recordShape) as removeMember and
 *  adoptRemovals write it: the caller its author, the server's time, and
 *  `removal: true`, which phones can never write. */
function removalDoc(r, uid, now) {
  return {
    type: r.type,
    hlc: { millis: r.hlc.millis, counter: r.hlc.counter, device: r.hlc.device },
    authorUid: uid, deviceId: r.deviceId, createdAt: now,
    payload: {
      version: r.payload.version, epoch: r.payload.epoch, flags: r.payload.flags,
      nonce: Buffer.from(r.payload.nonce, 'base64'), blob: Buffer.from(r.payload.blob, 'base64'),
    },
    removal: true,
  };
}

/** A checked phone-built profile record as shareProfile writes it: the
 *  caller its author, the server's time, and `profile: true`, which phones
 *  can never write. */
function profileDoc(r, uid, now) {
  const { removal: _, ...doc } = removalDoc(r, uid, now);
  return { ...doc, profile: true };
}

/** D-229: a checked phone-built placeAlias record as shareAliases writes it:
 *  the caller its author, the server's time, and `alias: true`, which phones
 *  can never write (firestore.rules, validRecord's hasOnly). */
function aliasDoc(r, uid, now) {
  const { removal: _, ...doc } = removalDoc(r, uid, now);
  return { ...doc, alias: true };
}

/** D-230: a checked phone-built heldPlace record as sharePlaces and
 *  acceptInvite write it: the caller its author, the server's time, and
 *  `held: true`, which phones can never write (validRecord's hasOnly). */
function heldDoc(r, uid, now, hlc) {
  const { removal: _, ...doc } = removalDoc(r, uid, now);
  return { ...doc, hlc, held: true };
}

/** The parsed JSON op a stored record's blob carries, or null. */
function opOf(doc) {
  try {
    return JSON.parse(Buffer.from(doc.payload?.blob ?? []).toString('utf8'));
  } catch {
    return null;
  }
}

/** D-229: the `you` ids account `u`'s own log (users/{u}/records, type 3)
 *  holds. Read server side, never returned, and outside any transaction (no
 *  locks on another account's data): personal records are create-only, so an
 *  id once held stays held. */
async function yousOf(u) {
  // D-235 (M1, round 2 C1): a page at a time (only the ids are kept), at
  // most YOUS_MAX records; past that the rest is not read and what was read
  // is used, never a refusal (a flood of one's own log hides only one's own
  // ids).
  const ids = new Set();
  const all = db.collection('users').doc(u).collection('records').where('type', '==', '3')
    .orderBy(FieldPath.documentId()).limit(YOUS_PAGE);
  let seen = 0;
  let last = null;
  for (;;) {
    const page = await (last ? all.startAfter(last) : all).get();
    for (const d of page.docs) {
      const op = opOf(d.data());
      if (op?.op === 'you' && typeof op.id === 'string') ids.add(op.id);
    }
    seen += page.size;
    if (page.size < YOUS_PAGE || seen >= YOUS_MAX) return ids;
    last = page.docs[page.docs.length - 1];
  }
}

/** Whether a stored record is a memberProfile op of group `groupId`, and of
 *  place `member` when one is given (a person has one preview per place). */
function isProfileOp(doc, groupId, member = null) {
  try {
    const op = JSON.parse(Buffer.from(doc.payload?.blob ?? []).toString('utf8'));
    return op?.op === 'memberProfile' && op.group === groupId && (member === null || op.member === member);
  } catch {
    return false;
  }
}

/** Refuses what `can` refuses: a no-op (already done, most likely on another
 *  phone) is failed-precondition; anything else is permission-denied. */
function allow(action, group, caller, target) {
  if (groups.can(action, group, caller, target)) return;
  const admins = groups.adminsOf(group);
  const noOp = (action === 'admin-on' && admins.includes(target)
      && groups.can(action, { ...group, admins: admins.filter((a) => a !== target) }, caller, target))
    || (action === 'admin-off' && !admins.includes(target) && (group.memberUids ?? []).includes(target)
      && groups.can(action, { ...group, admins: [...admins, target] }, caller, target));
  throw new HttpsError(noOp ? 'failed-precondition' : 'permission-denied', noOp ? CHANGED : ROLE_REFUSED);
}

/** Runs a side effect of a change that has already committed (notices, closing
 *  requests): a failure is logged, never thrown, so the call still succeeds. */
async function afterCommit(fn, step, work) {
  try {
    await work();
  } catch (e) {
    logger.warn(`${fn}: ${step} failed after the change committed`, { code: typeof e?.code === 'string' || typeof e?.code === 'number' ? e.code : 'other' });
  }
}

/** Writes a notice to each uid and pushes it to their phones (not to the
 *  caller's own: `quiet`). A request notice has one id on every recipient,
 *  so one delete per recipient clears it. A push failure never undoes what
 *  was written. */
async function tell(fn, uids, fields, { id = null, toPerson = false, quiet = [] } = {}) {
  const to = [...new Set(uids.filter((u) => typeof u === 'string' && u))];
  if (!to.length) return;
  const notice = { ...fields, at: FieldValue.serverTimestamp() };
  await Promise.all(to.map((u) => {
    const c = db.collection('users').doc(u).collection('notices');
    return id ? c.doc(id).set(notice) : c.add(notice);
  }));
  const loud = to.filter((u) => !quiet.includes(u));
  try {
    const phones = (await Promise.all(loud.map(async (u) => {
      const list = await db.collection('users').doc(u).collection('devices').get();
      return list.docs.map((d) => ({ uid: u, id: d.id, data: d.data() }));
    }))).flat();
    const sent = push.tokens(phones);
    if (!sent.length) return;
    const response = await getMessaging().sendEachForMulticast({
      ...groups.noticePush({ ...fields, toPerson }), tokens: sent.map((s) => s.token),
    });
    report(fn, sent, response);
    await Promise.all(push.deadTokens(sent, response).map((s) =>
      db.collection('users').doc(s.uid).collection('devices').doc(s.id)
        .update({ fcmToken: FieldValue.delete() }).catch(() => {})));
  } catch (e) {
    logger.warn(`${fn}: push threw`, { code: typeof e?.code === 'string' ? e.code : 'other' });
  }
}

/** Deletes request `member`'s notice from each uid. */
async function forgetRequest(groupId, member, uids) {
  const id = groups.requestNoticeId(groupId, member);
  await Promise.all([...new Set(uids.filter(Boolean))].map((u) =>
    db.collection('users').doc(u).collection('notices').doc(id).delete()));
}

/** A new owner or admin sees the open requests (no push: they are not
 *  news); one losing admin no longer does, unless they asked or are named.
 *  D-227: "named" is a place account `uid` holds (placesOf), never a `you`
 *  compared as text: a phone may name the same account by its participant id
 *  or by its own claim's `you`. */
async function showRequests(ref, groupId, uid, show) {
  // D-235 (M2): the group's name as the server reads it (groupNameOf).
  const { mine, groupName } = await db.runTransaction(async (tx) => {
    const g = await again(tx, ref);
    const events = await opsOf(tx, ref, g);
    return { mine: await placesOf(tx, ref, g, uid, events), groupName: groups.groupNameOf(events, g.name) };
  }, { readOnly: true });
  const open = await ref.collection('requests').get();
  await Promise.all(open.docs.map((d) => {
    const r = d.data();
    if (r.askerUid === uid || mine.includes(r.member)) return null;
    const notice = db.collection('users').doc(uid).collection('notices').doc(groups.requestNoticeId(groupId, r.member));
    if (!show) return notice.delete();
    return notice.set({
      kind: 'request', groupId, groupName, byName: r.askerName, memberId: r.member, memberName: r.memberName,
      at: r.at ?? FieldValue.serverTimestamp(),
    });
  }));
}

/** Who must hear about a group's requests: its owner and admins, members only. */
function managers(group) {
  const members = group.memberUids ?? [];
  return [groups.ownerOf(group), ...groups.adminsOf(group)].filter((u) => members.includes(u));
}

// The owner and the admins, as participant ids (what the phones know; D-210,
// unchanged for 0.4.0 builds). D-227: and `places`, the places each holds in
// this group (placesOf): `owner`, `admins` (the admins but the owner, as
// `admins`) and `you` (the caller's), so a phone compares a row's place, not a
// participant id that may be another phone's "you".
export const groupRoles = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const { ref } = await groupOf(hex(request.data?.groupId, 'groupId'), uid);
  // One snapshot: the group, its log, the invites and the profiles are all read
  // in this read-only transaction, so `owner`, `admins` and `places` agree.
  return db.runTransaction(async (tx) => {
    const group = await again(tx, ref);
    if (!(group.memberUids ?? []).includes(uid)) {
      throw new HttpsError('permission-denied', 'Only a member of this group can do that.');
    }
    const owner = groups.ownerOf(group);
    const adminUids = groups.adminsOf(group).filter((u) => u !== owner);
    const admins = [];
    for (const u of adminUids) admins.push(await participantIn(tx, u));
    const events = await opsOf(tx, ref, group);
    const of = (u) => placesOf(tx, ref, group, u, events);
    const adminPlaces = [];
    for (const u of adminUids) adminPlaces.push(...await of(u));
    const places = { owner: owner ? await of(owner) : [], admins: adminPlaces, you: await of(uid) };
    return { owner: owner ? await participantIn(tx, owner) : null, admins: admins.filter(Boolean), places };
  }, { readOnly: true });
});

/** The account that holds place `member`, read in `tx` (placeIn), for a
 *  role change: `you` must be that account's own word for itself (placeIn;
 *  D-227: its participant id, its own claim's `you`, or its creator place),
 *  and a place with no account here is not-found. The phone names both; the server
 *  decides whose place it is (D-210 final review I1). */
async function holderFor(tx, ref, group, member, you) {
  const place = await placeIn(tx, ref, group, member, you);
  if (!place.target) throw new HttpsError('not-found', 'No such person.');
  return place;
}

// Makes someone an admin (the owner or an admin), or takes it away (the
// owner). Only someone with an account in the group can be one. The phone
// names the place (`member`) and its account's participant id (`you`).
export const setAdmin = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const data = request.data ?? {};
  const groupId = hex(data.groupId, 'groupId');
  const member = hex(data.member, 'member');
  const you = hex(data.you, 'you');
  if (typeof data.on !== 'boolean') throw new HttpsError('invalid-argument', 'on is not a boolean');
  text(data.groupName, 80, 'groupName'); // checked as before; notices use the server's (D-235 M2)
  const { ref } = await groupOf(groupId, uid);
  const action = data.on ? 'admin-on' : 'admin-off';
  const { admins, target, groupName } = await db.runTransaction(async (tx) => {
    const fresh = await again(tx, ref);
    const place = await holderFor(tx, ref, fresh, member, you);
    allow(action, fresh, uid, place.target);
    const t = place.target;
    const next = data.on ? [...groups.adminsOf(fresh), t] : groups.adminsOf(fresh).filter((a) => a !== t);
    tx.update(ref, { admins: data.on ? FieldValue.arrayUnion(t) : FieldValue.arrayRemove(t) });
    return { admins: next, target: t, groupName: groups.groupNameOf(place.events, fresh.name) };
  });
  // The change is committed: nothing below may fail the call (a retry would be
  // refused). Each step is its own.
  await afterCommit('setAdmin', 'telling the new admin', async () => {
    const byName = await nameOf(uid);
    if (data.on) await tell('setAdmin', [target], { kind: 'role', groupId, groupName, byName, role: 'admin' }, { quiet: [uid] });
  });
  await afterCommit('setAdmin', 'showing requests', () => showRequests(ref, groupId, target, data.on));
  return { admins: (await Promise.all(admins.map(participantOf))).filter(Boolean) };
});

// Hands the group to another member (the owner alone); the old owner becomes
// an admin. One owner. The phone names the place (`member`) and its
// account's participant id (`you`), as for setAdmin. An open request to
// remove the new owner is closed: nobody asks to remove the owner.
export const makeOwner = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const data = request.data ?? {};
  const groupId = hex(data.groupId, 'groupId');
  const member = hex(data.member, 'member');
  const you = hex(data.you, 'you');
  text(data.groupName, 80, 'groupName'); // checked as before; notices use the server's (D-235 M2)
  const { ref } = await groupOf(groupId, uid);
  const { target, person, groupName } = await db.runTransaction(async (tx) => {
    const fresh = await again(tx, ref);
    const place = await holderFor(tx, ref, fresh, member, you);
    allow('owner', fresh, uid, place.target);
    // One update may not transform one field twice: the new list is computed.
    const admins = [...groups.adminsOf(fresh).filter((a) => a !== place.target && a !== uid), uid];
    tx.update(ref, { owner: place.target, admins });
    return { target: place.target, person: place.person, groupName: groups.groupNameOf(place.events, fresh.name) };
  });
  // The hand-over is committed: nothing below may fail the call (the phone's
  // retry would be refused, it is no longer the owner). Each step is its own.
  await afterCommit('makeOwner', 'closing requests', () => store.closeRequestsAbout(db, groupId, target));
  await afterCommit('makeOwner', 'telling the new owner', async () => {
    const byName = await nameOf(uid);
    await tell('makeOwner', [target], { kind: 'role', groupId, groupName, byName, role: 'owner' }, { quiet: [uid] });
  });
  await afterCommit('makeOwner', 'showing requests', () => showRequests(ref, groupId, target, true));
  return { owner: person };
});

// A member asks for someone's removal (themselves included; never the
// owner's). One open request per person. The asker, the owner, the admins
// and the person named see it.
export const requestRemoval = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const data = request.data ?? {};
  const groupId = hex(data.groupId, 'groupId');
  const member = hex(data.member, 'member');
  const you = data.you == null ? null : hex(data.you, 'you');
  const memberName = text(data.memberName, 80, 'memberName');
  text(data.groupName, 80, 'groupName'); // checked as before; notices use the server's (D-235 M2)
  const { ref } = await groupOf(groupId, uid);
  const askerName = await nameOf(uid);
  const requestRef = ref.collection('requests').doc(member);
  // Whose place it is and whether it may be asked about are decided on what
  // this transaction read.
  const { fresh, target, groupName } = await db.runTransaction(async (tx) => {
    const g = await again(tx, ref);
    const place = await placeIn(tx, ref, g, member, you);
    const open = await tx.get(requestRef);
    if (!place.place) throw new HttpsError('not-found', 'No such person.');
    allow('request', g, uid, place.target);
    // Removed already (removeMember's own condition): nothing left to ask.
    if (place.removed && !place.target) throw new HttpsError('failed-precondition', CHANGED);
    if (open.exists) throw new HttpsError('already-exists', 'Already asked.');
    tx.create(requestRef, {
      member, you: you ?? place.person, memberName, askerUid: uid, askerName, at: FieldValue.serverTimestamp(),
    });
    return { fresh: g, target: place.target, groupName: groups.groupNameOf(place.events, g.name) };
  });
  const fields = { kind: 'request', groupId, groupName, byName: askerName, memberId: member, memberName };
  const id = groups.requestNoticeId(groupId, member);
  // The request is written: the notices after it may not fail the call (a retry
  // would be refused as already asked).
  await afterCommit('requestRemoval', 'telling the managers', () =>
    tell('requestRemoval', [...managers(fresh), uid].filter((u) => u !== target), fields, { id, quiet: [uid] }));
  if (target) {
    await afterCommit('requestRemoval', 'telling the person', () =>
      tell('requestRemoval', [target], fields, { id, toPerson: true, quiet: [uid] }));
  }
  return { requested: true };
});

// The owner or an admin keeps the person (the owner alone when the person
// is an admin): the request and its notices go; the asker and the person
// are told.
export const declineRemoval = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const data = request.data ?? {};
  const groupId = hex(data.groupId, 'groupId');
  const member = hex(data.member, 'member');
  text(data.groupName, 80, 'groupName'); // checked as before; notices use the server's (D-235 M2)
  const { ref } = await groupOf(groupId, uid);
  const requestRef = ref.collection('requests').doc(member);
  // Whose place it is NOW decides (not the `you` the request stored): a
  // place that has since joined and become an admin is the owner's to keep.
  const { g, r, target, groupName } = await db.runTransaction(async (tx) => {
    const fresh = await again(tx, ref);
    const open = await tx.get(requestRef);
    if (!open.exists) throw new HttpsError('not-found', 'No such request.');
    const place = await placeIn(tx, ref, fresh, member, null);
    allow('decline', fresh, uid, place.target);
    tx.delete(requestRef);
    return { g: fresh, r: open.data(), target: place.target, groupName: groups.groupNameOf(place.events, fresh.name) };
  });
  // The request is deleted: nothing below may fail the call (a retry would be
  // not-found). Each step is its own.
  await afterCommit('declineRemoval', 'clearing the notices', () =>
    forgetRequest(groupId, member, [...(g.memberUids ?? []), r.askerUid, target]));
  await afterCommit('declineRemoval', 'telling the asker and the person', async () => {
    const byName = await nameOf(uid);
    const fields = { kind: 'declined', groupId, groupName, byName, memberId: member, memberName: r.memberName };
    if (r.askerUid !== uid && r.askerUid !== target) await tell('declineRemoval', [r.askerUid], fields, { quiet: [uid] });
    if (target && target !== uid) await tell('declineRemoval', [target], fields, { toPerson: true, quiet: [uid] });
  });
  return { declined: true };
});

// Removes someone (the owner or an admin; never the owner; an admin only by
// the owner). In one transaction: the phone-built records (exactly this
// removal and the person's redistributions) are written with `removal: true`,
// which phones can never write, and the person's account leaves the members
// for `former`, so they keep reading what was written up to now.
export const removeMember = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const data = request.data ?? {};
  const groupId = hex(data.groupId, 'groupId');
  const member = hex(data.member, 'member');
  const you = data.you == null ? null : hex(data.you, 'you');
  const memberName = text(data.memberName, 80, 'memberName');
  text(data.groupName, 80, 'groupName'); // checked as before; notices use the server's (D-235 M2)
  if (groups.checkRemovalRecords(data.records, groupId, member)) throw new HttpsError('invalid-argument', 'records are not valid');
  const { ref, group } = await groupOf(groupId, uid);
  const requestRef = ref.collection('requests').doc(member);
  let asked = null;
  let target = null;
  let groupName = null;
  try {
    ({ asked, target, groupName } = await db.runTransaction(async (tx) => {
      const g = await again(tx, ref);
      const place = await placeIn(tx, ref, g, member, you);
      const open = await tx.get(requestRef);
      // Only a place the group's log made (as requestRemoval), or (round 4,
      // R3-2) one an account holds through an accepted, unended invite (the
      // server's own record), whatever the log shows: an author whose ops are
      // not read (purged before `departed`, or past its bound) never makes
      // that account unremovable.
      if (!place.place && !place.target) throw new HttpsError('not-found', 'No such person.');
      allow('remove', g, uid, place.target);
      // Removed already (by another phone): the place has no account here
      // any more, or never had one.
      if (place.removed && !place.target) throw new HttpsError('failed-precondition', CHANGED);
      const t = place.target;
      // Round 3 (R2-1) as fixed in round 4 (R3-1): the removal of a place no
      // account holds (a placeholder) is counted and capped; the removal of
      // an ACCOUNT always goes through and is not counted, so no manager can
      // fill the cap and become unremovable. Account removals are bounded by
      // the accounts that ever joined (each through an accepted invite).
      if (!t && (g.removals ?? 0) + data.records.length > REMOVALS_MAX) throw new HttpsError('resource-exhausted', REMOVALS_FULL);
      const now = FieldValue.serverTimestamp();
      for (const r of data.records) tx.create(ref.collection('records').doc(r.id), removalDoc(r, uid, now));
      if (t) {
        tx.update(ref, 'memberUids', FieldValue.arrayRemove(t), 'admins', FieldValue.arrayRemove(t),
          new FieldPath('former', t), { at: now, by: uid });
      } else {
        tx.update(ref, { removals: FieldValue.increment(data.records.length) });
      }
      if (open.exists) tx.delete(requestRef);
      // The removed account's used invites for this place end, so a later
      // join of that account elsewhere never makes it this place again.
      if (t) place.links.filter((d) => d.data().usedBy === t && !d.data().endedAt).forEach((d) => tx.update(d.ref, { endedAt: now }));
      return { asked: open.exists ? open.data() : null, target: t, groupName: groups.groupNameOf(place.events, g.name) };
    }));
  } catch (e) {
    // tx.create refuses a record id the log already holds.
    if (!(e instanceof HttpsError) && e?.code === 6) throw new HttpsError('already-exists', 'Already recorded.');
    throw e;
  }
  // The removal is committed: nothing below may fail the call (a retry would be
  // refused as already removed). Each step is its own.
  await afterCommit('removeMember', 'clearing the notices', () =>
    forgetRequest(groupId, member, [...(group.memberUids ?? []), asked?.askerUid, target]));
  await afterCommit('removeMember', 'voiding invitations', async () => {
    // Invitations to this place that nobody used are void now, and (D-235 H1
    // hygiene) every unused one the removed account made in this group: a
    // removed member adds nobody. Each goes with its in-app copy (L2).
    const links = await db.collection('invites').where('groupId', '==', groupId).where('memberId', '==', member).get();
    const theirs = target
      ? (await db.collection('invites').where('groupId', '==', groupId).where('createdBy', '==', target).get()).docs : [];
    const unused = new Map([...links.docs, ...theirs].filter((d) => !d.data().usedBy).map((d) => [d.id, d]));
    await store.voidLinks(db, [...unused.values()]);
    if (target) {
      const copies = await db.collection('users').doc(target).collection('invites')
        .where('groupId', '==', groupId).where('memberId', '==', member).get();
      await Promise.all(copies.docs.map((d) => d.ref.delete()));
    }
  });
  if (target) {
    await afterCommit('removeMember', 'telling the removed person', async () => {
      const removedAt = (await ref.get()).data()?.former?.[target]?.at ?? null;
      const byName = await nameOf(uid);
      await tell('removeMember', [target],
        { kind: 'removed', groupId, groupName, byName, memberId: member, memberName, removedAt }, { quiet: [uid] });
    });
  }
  return { removed: true };
});

/** Ends group `groupId`'s invitations once it is deleted: the link invites
 *  nobody used, and every in-app copy (users/{u}/invites) on the accounts of
 *  `uids` and of whoever an invite was delivered to (`deliveredTo`). A used
 *  invite stays: it is history. */
async function endInvites(groupId, uids) {
  const links = await db.collection('invites').where('groupId', '==', groupId).get();
  await Promise.all(links.docs.filter((d) => !d.data().usedBy).map((d) => d.ref.delete()));
  const holders = new Set([...uids, ...links.docs.map((d) => d.data().deliveredTo)].filter((u) => typeof u === 'string' && u));
  await Promise.all([...holders].map(async (u) => {
    const copies = await db.collection('users').doc(u).collection('invites').where('groupId', '==', groupId).get();
    await Promise.all(copies.docs.map((d) => d.ref.delete()));
  }));
}

// Deletes the group for everyone (the owner alone), open balances or not:
// every member keeps a read-only copy on their phone. The group goes first;
// only once it is gone is anyone told. Its picture and invitations go too,
// and neither failing fails the deletion.
export const deleteGroup = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const data = request.data ?? {};
  const groupId = hex(data.groupId, 'groupId');
  text(data.groupName, 80, 'groupName'); // checked as before; notices use the server's (D-235 M2)
  const { ref, group } = await groupOf(groupId, uid);
  allow('delete', group, uid, null);
  // D-235 (M2): the name read from the log before it goes. Deleting never
  // fails on the log: on any failure, the document's name.
  const groupName = await db.runTransaction(async (tx) => groups.groupNameOf(await opsOf(tx, ref, group), group.name), { readOnly: true })
    .catch(() => groups.groupNameOf([], group.name));
  const open = (await ref.collection('requests').get()).docs.map((d) => d.id);
  const members = group.memberUids ?? [];
  const everyone = [...members, ...Object.keys(group.former ?? {})];
  const byName = await nameOf(uid);
  await db.recursiveDelete(ref);
  await store.deleteGroupPicture(groupId);
  // The group is gone: nothing below may fail the call. Each step is its own.
  await afterCommit('deleteGroup', 'ending invitations', () => endInvites(groupId, everyone));
  await afterCommit('deleteGroup', 'telling the members', () =>
    tell('deleteGroup', members.filter((u) => u !== uid), { kind: 'deleted', groupId, groupName, byName }, { quiet: [uid] }));
  await afterCommit('deleteGroup', 'clearing the requests', () =>
    Promise.all(open.map((member) => forgetRequest(groupId, member, everyone))));
  return { deleted: true };
});

// D-210 final review (I4): the removals a phone made while a group was only
// on it (before anyone else joined, so before it was ever shared) reach the
// cloud with the removal mark, which phones can never write and every phone
// requires before it applies a removal. Only while the caller is the group's
// one member: once others are in, removing goes through removeMember.
// Records: 1 to 50, each a removeMember op or a redistribution of this group
// (groups.checkAdoptedRecords). Written in one transaction, all or nothing;
// a record id already in the log is ALREADY_EXISTS (a retry: done).
export const adoptRemovals = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const data = request.data ?? {};
  const groupId = hex(data.groupId, 'groupId');
  if (groups.checkAdoptedRecords(data.records, groupId)) throw new HttpsError('invalid-argument', 'records are not valid');
  const { ref } = await groupOf(groupId, uid);
  try {
    await db.runTransaction(async (tx) => {
      const g = await again(tx, ref);
      const members = g.memberUids ?? [];
      // Round 3 (R2-1): only while the group has never had anyone else: no
      // other member now, none removed (`former`) or purged (`departed`), no
      // invite ever used, and the caller its creator.
      const joined = await tx.get(db.collection('invites').where('groupId', '==', ref.id).where('usedBy', '>', '').limit(1));
      if (members.length !== 1 || members[0] !== uid || g.createdBy !== uid || Object.keys(g.former ?? {}).length
        || (g.departed ?? []).length || !joined.empty) {
        throw new HttpsError('failed-precondition', 'Others are in this group now.');
      }
      if ((g.removals ?? 0) + data.records.length > REMOVALS_MAX) throw new HttpsError('resource-exhausted', REMOVALS_FULL);
      const now = FieldValue.serverTimestamp();
      for (const r of data.records) tx.create(ref.collection('records').doc(r.id), removalDoc(r, uid, now));
      tx.update(ref, { removals: FieldValue.increment(data.records.length) });
    });
  } catch (e) {
    // tx.create refuses a record id the log already holds.
    if (!(e instanceof HttpsError) && e?.code === 6) throw new HttpsError('already-exists', 'Already recorded.');
    throw e;
  }
  return { adopted: data.records.length };
});

// D-211: writes the caller's own name and preview into each group where the
// server says they hold the place the record names. The name must be the
// account's own (users/{uid}.name). A group that refuses (not a member, not
// their place, contested) is skipped; the others are written. Founder
// decision 5 (2026-10-14): in the same transaction the caller's other marked
// profiles in that group FOR THE SAME PLACE are deleted, so the group holds
// one per place: the latest by HLC, the one every phone shows
// (groups.profilePlan). A preview on another place (one the account was
// removed from) stays: removed people keep the name and preview their last
// profile gave them. A late
// retry of an earlier record, or an older one from a phone that is behind,
// writes nothing and deletes nothing newer. Phones never act on a deletion.
export const shareProfile = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const data = request.data ?? {};
  const self = await db.collection('users').doc(uid).get();
  const name = self.exists && typeof self.data().name === 'string' ? self.data().name : null;
  const you = self.exists && typeof self.data().participantId === 'string' ? self.data().participantId : null;
  const bad = groups.checkProfileRecords(data.records, name);
  if (bad === 'records') throw new HttpsError('invalid-argument', 'records are not valid');
  if (!name || !you || bad === 'name') throw new HttpsError('failed-precondition', 'Your name changed. Try again.');
  const shared = [];
  const skipped = [];
  for (const r of data.records) {
    const b = groups.json(r.payload.blob);
    try {
      const { ref } = await groupOf(b.group, uid);
      await db.runTransaction(async (tx) => {
        const g = await again(tx, ref);
        const place = await placeIn(tx, ref, g, b.member, you);
        // A place the group's own log never made (removeMember's check too).
        if (!place.place) throw new HttpsError('not-found', 'No such person.');
        // Needed: participant ids are not unique and a phone writes its own,
        // so a member who copies another's id passes placeIn's `you` check
        // for the place that account holds through an invite. Only this stops
        // them writing a profile on it.
        if (place.target !== uid) throw new HttpsError('permission-denied', 'Not your place.');
        const marked = await tx.get(ref.collection('records').where('profile', '==', true));
        const mine = marked.docs.filter((d) => d.data().authorUid === uid && isProfileOp(d.data(), ref.id, b.member))
          .map((d) => ({ id: d.id, hlc: d.data().hlc, ref: d.ref }));
        const plan = groups.profilePlan(mine, r);
        for (const d of mine) if (plan.drop.includes(d.id)) tx.delete(d.ref);
        if (plan.write) tx.create(ref.collection('records').doc(r.id), profileDoc(r, uid, FieldValue.serverTimestamp()));
      });
      shared.push(b.group);
    } catch (e) {
      // Already written (a retry after a lost answer) counts as shared.
      if (!(e instanceof HttpsError) && e?.code === 6) { shared.push(b.group); continue; }
      // Not a member, not their place, contested, no such place: skipped.
      // Anything else (a transient failure, a bug) fails the whole call, so the
      // phone keeps its job and retries; groups already written then count as
      // shared through the case above. Only the code is logged, never ids.
      if (!(e instanceof HttpsError)) throw e;
      logger.warn('shareProfile: a group was skipped', { code: e.code });
      skipped.push(b.group);
    }
  }
  return { shared, skipped };
});

// D-229 (Phase B): one account on two phones may have written records into a
// group under an id the group does not know it by (the other phone's "you",
// adopted after the group was made under this one's). shareAliases writes, per
// record, a placeAlias op "in group G, id `you` reads as place `member`",
// marked `alias: true` (phones never write the mark and import the op only
// with it), so the other members' phones read those records as that place. No
// record is rewritten. A call is charged to the caller's daily `aliases` quota
// (decision 48, one per record, before anything is read). Written only when ALL
// hold:
// - the caller's own log (users/{uid}/records, type 3) holds a `you` op with id
//   `you` (read first: a random id costs one read);
// - `you` is no OTHER account's participant id (users/{u}.participantId),
//   member or not (decision 47: no member pre-aliases a future member's id;
//   D-237 A3: one that REGISTERED it before the caller's own profile);
// - in one transaction: the caller is a member and holds place `member`
//   (holderIn, D-227 as fixed by decisions 36 and 37), a place the log made and
//   not removed; no marked alias names `you` for another place (the same place:
//   already done, shared, nothing written); `you` is no place of the log; no
//   claim op another account wrote names `you`; no other current or former
//   member's own log holds `you` (a copy makes it contested for everyone: a
//   denial of the repair, never a takeover; former members count, so an id of
//   someone removed is never taken by who stayed); and the record id holds no
//   document yet (anything there is not this alias: skipped, never shared).
// Personal logs are read outside the transaction, once per account per call.
// The answer is the same skip for every refusal; the log carries only the
// code, never an id, and never whether another account's log holds an id.
// Records: 1 to 50 (groups.checkAliasRecords). NOT_YOURS, their one refusal,
// is declared above acceptInvite, which refuses a held place with it too.

export const shareAliases = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const data = request.data ?? {};
  if (groups.checkAliasRecords(data.records)) throw new HttpsError('invalid-argument', 'records are not valid');
  await charge(uid, 'aliases', data.records.length);
  const logs = new Map();
  const yous = (u) => {
    if (!logs.has(u)) logs.set(u, yousOf(u));
    return logs.get(u);
  };
  const shared = [];
  const skipped = [];
  for (const r of data.records) {
    const a = groups.json(r.payload.blob);
    try {
      const refuse = () => new HttpsError('permission-denied', NOT_YOURS);
      if (!(await yous(uid)).has(a.you)) throw refuse();
      // Decision 47, as D-237 (A3) reads it: another account's id when that account registered it first.
      if (await registeredBefore(uid, a.you)) throw refuse();
      const { ref } = await groupOf(a.group, uid);
      await db.runTransaction(async (tx) => {
        const g = await again(tx, ref);
        if (!(g.memberUids ?? []).includes(uid)) throw refuse();
        const events = await opsOf(tx, ref, g);
        const { uid: holder, log } = await holderIn(tx, ref, g, a.member, events);
        if (holder !== uid || !log.place || log.removed) throw refuse();
        const marked = await tx.get(ref.collection('records').where('alias', '==', true));
        const named = marked.docs.map((d) => opOf(d.data()))
          .filter((op) => op?.op === 'placeAlias' && op.group === ref.id && op.you === a.you);
        if (named.some((op) => op.member !== a.member)) throw refuse();
        if (named.length) return; // already done: shared, nothing written
        if ((await logOf(tx, ref, g, a.you, events)).place) throw refuse();
        if (log.claims.some((c) => c.you === a.you && c.author !== uid)) throw refuse();
        const accounts = [...new Set([...(g.memberUids ?? []), ...Object.keys(g.former ?? {})])];
        for (const u of accounts) {
          if (u !== uid && (await yous(u)).has(a.you)) throw refuse();
        }
        const doc = ref.collection('records').doc(r.id);
        if ((await tx.get(doc)).exists) throw refuse();
        tx.create(doc, aliasDoc(r, uid, FieldValue.serverTimestamp()));
      });
      shared.push(r.id);
    } catch (e) {
      // Not a member, not their place, not their id, contested: skipped.
      // Anything else (a transient failure, a bug, a record created in the
      // same instant) fails the whole call, so the phone asks again at its
      // next push, when the checks above see what is there. Only the code is
      // logged.
      if (!(e instanceof HttpsError)) throw e;
      logger.warn('shareAliases: a record was skipped', { code: e.code });
      skipped.push(r.id);
    }
  }
  return { shared, skipped };
});

/** D-230: the checks a held place shares between sharePlaces and the join:
 *  `you` the caller's own (its participant id or a `you` op in its own log); `you` no
 *  other account's participant id registered before the caller's (decision
 *  47's query as D-237 A3 reads it). `yous(u)` is the per-call cache. Throws
 *  the plain refusal. */
async function checkHeldId(r, a, uid, yous, refuse) {
  const own = (await yous(uid)).has(a.you) || a.you === await participantOf(uid);
  if (!own) throw refuse();
  // Decision 47, as D-237 (A3) reads it: another account's id when that account registered it first.
  if (await registeredBefore(uid, a.you)) throw refuse();
}

/** D-230: no other current or former member's own log holds `you`. */
async function heldByNobodyElse(g, uid, you, yous) {
  const accounts = [...new Set([...(g.memberUids ?? []), ...Object.keys(g.former ?? {})])];
  for (const u of accounts) {
    if (u !== uid && (await yous(u)).has(you)) return false;
  }
  return true;
}

/** D-230 A1: the invites naming place `member` of the group at `ref`, read in `tx`. */
async function linksOf(tx, ref, member) {
  return (await tx.get(db.collection('invites').where('groupId', '==', ref.id).where('memberId', '==', member))).docs
    .map((d) => d.data());
}

/** D-230 A1/A6: place `member` (`log` from logOf) is account `uid`'s own
 *  former creator place: ever removed (R3-M1: an add-back changes nothing,
 *  decision 64), the creator place of the group, `uid` its
 *  creator, and no other account ever joined it by invite (an invitee held
 *  it: decision 36, whatever ended since). */
async function creatorBefore(tx, ref, group, member, uid, log) {
  if (!log.everRemoved || member !== log.creatorPlace || group.createdBy !== uid) return false;
  return !(await linksOf(tx, ref, member)).some((i) => typeof i.usedBy === 'string' && i.usedBy && i.usedBy !== uid);
}

/** D-230 A1: how account `uid` held the EVER-REMOVED place `member` before, or
 *  null: 'creator' (creatorBefore), or 'invite' (an invite it used for the
 *  place that removeMember ended), and never when any other account ever
 *  used an invite for the place (A9, as creatorBefore: decision 64 means a
 *  removed place never changes hands, so this is defence in depth against
 *  history from before it). `log` from logOf for that place. */
async function heldBefore(tx, ref, group, member, uid, log) {
  if (!log.everRemoved) return null;
  if (await creatorBefore(tx, ref, group, member, uid, log)) return 'creator';
  const links = await linksOf(tx, ref, member);
  if (links.some((i) => typeof i.usedBy === 'string' && i.usedBy && i.usedBy !== uid)) return null;
  return links.some((i) => i.usedBy === uid && i.endedAt) ? 'invite' : null;
}

/** D-230 A3/A6: whether `you` (a.you) is refused as a raw place of the log:
 *  the place itself only for who holds it as creator (`asCreator`); another
 *  place of the log never, except the caller's own former creator place
 *  (creatorBefore: a creator removed from it and re-invited marks its new
 *  place with the "you" it made the group under). Without this a caller who
 *  copied a placeholder's id into its own log would take that placeholder's
 *  history through a mark. `events` from opsOf. */
async function rawYouRefused(tx, ref, g, a, uid, asCreator, events) {
  if (a.you === a.member) return !asCreator;
  const log = await logOf(tx, ref, g, a.you, events);
  return log.place && !(await creatorBefore(tx, ref, g, a.you, uid, log));
}

/** D-230: the marked held places of the group at `ref`, read in `tx`, as
 *  [{op, author}]. */
async function heldMarks(tx, ref) {
  const marked = await tx.get(ref.collection('records').where('held', '==', true));
  return marked.docs.map((d) => ({ op: opOf(d.data()), author: d.data().authorUid }))
    .filter((m) => m.op?.op === 'heldPlace' && m.op.group === ref.id);
}

/** D-230 decision 66: whether a mark another account wrote (`marked` from
 *  heldMarks) names `you` on a member still present (removedSince: not
 *  removed). The first account to mark an id keeps it; the same account's
 *  own marks never count. `events` from opsOf. */
async function namedByAnother(tx, ref, g, uid, you, marked, events) {
  for (const m of marked) {
    if (m.author === uid || m.op.you !== you) continue;
    if (!(await logOf(tx, ref, g, m.op.member, events)).removed) return true;
  }
  return false;
}

/** D-230: the last checks both writers make, read in `tx`: no other
 *  account's mark names `you` on a present member (decision 66); the same
 *  (member, you) not marked already (null: already done, nothing to write);
 *  no other current or former member's log holds `you`. Decision 54 as
 *  reworded (A10): the server stamps the mark's identity with its own clock
 *  and a random device per record, so phone clocks play no part and the
 *  latest per member is the server's latest; the document id follows it.
 *  Within one call the record's index in the list is the counter, so marks
 *  stamped in the same millisecond order as the list (the last wins).
 *  Returns { doc, hlc }. */
async function heldMarkDoc(tx, ref, g, r, a, uid, yous, refuse, events, index) {
  const marked = await heldMarks(tx, ref);
  if (await namedByAnother(tx, ref, g, uid, a.you, marked, events)) throw refuse();
  if (marked.some((m) => m.op.member === a.member && m.op.you === a.you)) return null;
  if (!(await heldByNobodyElse(g, uid, a.you, yous))) throw refuse();
  const hlc = { millis: Date.now(), counter: index, device: randomBytes(16).toString('hex') };
  const doc = ref.collection('records').doc(`${hlc.millis}-${hlc.counter}-${hlc.device}`);
  if ((await tx.get(doc)).exists) throw refuse();
  return { doc, hlc };
}

// D-230: the server's word on who holds a place (CLOUD-SECURITY G-08). Per
// record a heldPlace op "in group G, place `member` is held by id `you`",
// marked `held: true`, its identity stamped by the server (decision 54 as
// reworded: the server's clock, a random device). Written only when ALL
// hold: `you` is the caller's own id and no other account's
// participant id (decision 47's query); in one transaction the caller is a
// member and holds `member` (holderIn: D-227 as fixed by decisions 36 and
// 37; contested throws CHANGED and is skipped), a place the log made, or, when
// the place is removed, held it before (A1, heldBefore); `you` is the place's
// own id only when the caller holds it as its creator (the creator place is
// its phone's "you" at creation; held by invite, a raw place id is nobody's
// "you") and no other place of the log unless it is the caller's own former
// creator place (A3, A6: rawYouRefused); no other current or former
// member's log holds `you` (a copy is a denial, never a takeover); the same
// (member, you) is not marked already (already done: shared, nothing
// written; another `you` for the same member IS written: a two-phone
// account's other id, or the place's new holder, and core takes the latest);
// and no other account's mark names `you` on a present member (decision 66:
// the first account to mark an id keeps it). Charged to the caller's daily
// `places` quota (decision 55) before anything is read. The answer is the
// same skip for every refusal; the log carries only the code.
export const sharePlaces = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const data = request.data ?? {};
  if (groups.checkHeldRecords(data.records)) throw new HttpsError('invalid-argument', 'records are not valid');
  await charge(uid, 'places', data.records.length);
  const logs = new Map();
  const yous = (u) => {
    if (!logs.has(u)) logs.set(u, yousOf(u));
    return logs.get(u);
  };
  const shared = [];
  const skipped = [];
  for (const [i, r] of data.records.entries()) {
    const a = groups.json(r.payload.blob);
    try {
      const refuse = () => new HttpsError('permission-denied', NOT_YOURS);
      await checkHeldId(r, a, uid, yous, refuse);
      const { ref } = await groupOf(a.group, uid);
      const written = await db.runTransaction(async (tx) => {
        const g = await again(tx, ref);
        if (!(g.memberUids ?? []).includes(uid)) throw refuse();
        const events = await opsOf(tx, ref, g);
        const { uid: holder, creator, log } = await holderIn(tx, ref, g, a.member, events);
        if (!log.place) throw refuse();
        let asCreator = creator;
        if (log.everRemoved && (log.removed || holder !== uid)) {
          // A1: a place ever removed only for who held it before (R3-M1:
          // even added back, since decision 64 nobody can join it again;
          // a present holder of an added-back place from before decision
          // 64 keeps the present path).
          const before = await heldBefore(tx, ref, g, a.member, uid, log);
          if (!before) throw refuse();
          asCreator = before === 'creator';
        } else if (holder !== uid) {
          throw refuse();
        }
        if (await rawYouRefused(tx, ref, g, a, uid, asCreator, events)) throw refuse();
        const mark = await heldMarkDoc(tx, ref, g, r, a, uid, yous, refuse, events, i);
        if (!mark) return null; // already done: shared, nothing written
        tx.create(mark.doc, heldDoc(r, uid, FieldValue.serverTimestamp(), mark.hlc));
        return mark.doc.id;
      });
      // The phone's record id, never the one the server stamped (`written`,
      // null when already done): the phone maps its answer by the ids it sent.
      shared.push(r.id);
    } catch (e) {
      // As shareAliases: a refusal is a skip; anything else fails the call,
      // so the phone asks again at its next push.
      if (!(e instanceof HttpsError)) throw e;
      logger.warn('sharePlaces: a record was skipped', { code: e.code });
      skipped.push(r.id);
    }
  }
  return { shared, skipped };
});
