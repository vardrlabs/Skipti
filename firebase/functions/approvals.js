// D-254 (decisions 228, 229): the approval gate's decisions, worked out
// without touching Firebase, so firebase/tests/approvals.test.mjs runs each
// one directly. index.js reads and writes approvals/{uid} and asks these.
//
// Anyone can sign in; until the founder approves the account, the cloud does
// nothing for it. The rules refuse every client write of an unapproved
// account; index.js refuses every callable but the ones that let a person
// leave (and the two that ask for and grant approval).

export const STATES = ['pending', 'approved', 'denied'];

/** The callables an unapproved account may still call: leaving never waits
 *  (sign out, delete, keep), asking for approval, and the admin's own call
 *  (which checks its own caller). Every other onCall starts with
 *  approvedCaller; approvals.test.mjs reads index.js and proves it. */
export const EXEMPT = ['signOutDevice', 'signOutEverywhere', 'requestDeletion', 'cancelDeletion', 'requestApproval', 'adminApprovals'];

/** What the gate reads of an approvals document (its data, or null when
 *  there is none): one of STATES, or 'none' (no document, or one holding
 *  only a deletion marker: the account never asked). */
export function stateOf(data) {
  return data && STATES.includes(data.state) ? data.state : 'none';
}

/** The refusal an unapproved caller gets, or null when approved. The words
 *  are shown to people (both phones map `details.approval` to their own). */
export function refusal(state) {
  if (state === 'approved') return null;
  return {
    message: state === 'denied' ? 'This account was not approved.' : 'This account is waiting for approval.',
    details: { approval: state },
  };
}

/** requestApproval's input, checked: `platform` 'ios' or 'android'; `name`
 *  the name the phone shows others, trimmed, 1 to 60 characters, or absent
 *  (null, undefined or blank). `token` is the caller's decoded ID token:
 *  the provider is its sign_in_provider; the email is kept only when the
 *  provider said it is verified. Returns { error } or the fields. */
export function requestFields(data, token) {
  const platform = data?.platform;
  if (platform !== 'ios' && platform !== 'android') return { error: 'platform is not valid' };
  const raw = data?.name;
  let name = null;
  if (raw != null) {
    if (typeof raw !== 'string') return { error: 'name is not valid' };
    const trimmed = raw.trim();
    if (trimmed.length > 60) return { error: 'name is not valid' };
    name = trimmed || null;
  }
  const provider = typeof token?.firebase?.sign_in_provider === 'string' ? token.firebase.sign_in_provider : null;
  const email = token?.email_verified === true && typeof token?.email === 'string' && token.email ? token.email : null;
  return { name, platform, provider, email };
}

/** What requestApproval writes over the stored document (`data`, or null):
 *  no decision yet and none asked ('none'): a new pending request; pending:
 *  the name, platform and email refreshed (never requestedAt); decided:
 *  nothing (null). `now` is the server-time sentinel; `drop` the field-delete
 *  sentinel, for a name or email no longer given. */
export function requestWrite(data, fields, now, drop) {
  const state = stateOf(data);
  if (state === 'approved' || state === 'denied') return null;
  const optional = { name: fields.name ?? drop, email: fields.email ?? drop };
  if (state === 'pending') return { platform: fields.platform, ...optional };
  const fresh = { state: 'pending', requestedAt: now, platform: fields.platform };
  if (fields.provider) fresh.provider = fields.provider;
  if (fields.name) fresh.name = fields.name;
  if (fields.email) fresh.email = fields.email;
  return fresh;
}

/** Whether the caller is the admin: its uid is the configured one (never an
 *  empty value: an unset param refuses everyone) AND it signed in with
 *  Google (an Apple account given the same uid is not the admin). */
export function isAdmin(uid, provider, adminUid) {
  const admin = typeof adminUid === 'string' ? adminUid.trim() : '';
  return admin !== '' && typeof uid === 'string' && uid === admin && provider === 'google.com';
}

/** The origins adminApprovals answers (its CORS list): the admin site's two
 *  hosts, and the page served locally against the emulators (localhost or
 *  127.0.0.1, any port). Exact strings and anchored patterns, so no other
 *  site's page can read an answer, even one that holds the admin's token. */
export const ADMIN_ORIGINS = [
  'https://skipti-26-admin.web.app', 'https://skipti-26-admin.firebaseapp.com',
  /^http:\/\/localhost(:\d+)?$/, /^http:\/\/127\.0\.0\.1(:\d+)?$/,
];

/** adminApprovals' refusal of anyone else: the caller's own id, so the
 *  founder can copy his into the param on deploy day. */
export const NOT_ADMIN = 'This is not the admin account.';

const millis = (t) => (typeof t?.toMillis === 'function' ? t.toMillis() : null);

/** One row of the admin list: the stored fields plus `uid`, times as millis.
 *  Optional fields are left out when absent. */
export function row(uid, data) {
  const out = { uid, state: data.state, requestedAt: millis(data.requestedAt) ?? 0 };
  for (const k of ['name', 'platform', 'provider', 'email', 'decidedBy']) {
    if (typeof data[k] === 'string' && data[k]) out[k] = data[k];
  }
  const decided = millis(data.decidedAt);
  if (decided !== null) out.decidedAt = decided;
  return out;
}

/** The list's rows, newest request first, at most `max`. */
export function newestFirst(rows, max = 500) {
  return [...rows].sort((a, b) => b.requestedAt - a.requestedAt || (a.uid < b.uid ? -1 : 1)).slice(0, max);
}

/** Whether the migration (fix wave M1: it runs again whenever asked)
 *  approves a profile whose approvals document is `data` (or null): no
 *  document or no decision yet (its profile
 *  predates the gate: the rules refuse a profile to an unapproved account);
 *  a denied or approved one is left alone. */
export function migrates(data) {
  const state = stateOf(data);
  return state === 'none' || state === 'pending';
}

/** The admin's decision, checked: { uid, state } or { error }. */
export function decision(data, uidPattern) {
  const uid = data?.uid;
  if (typeof uid !== 'string' || !uidPattern.test(uid)) return { error: 'uid is not valid' };
  if (data?.state !== 'approved' && data?.state !== 'denied') return { error: 'state is not valid' };
  return { uid, state: data.state };
}

/** Batches of at most 400 writes (Firestore allows 500; migrate keeps room). */
export const BATCH = 400;

/** The admin list reads at most this many documents a state: the newest
 *  requests, ordered in the query (fix wave M4: the composite index
 *  approvals (state ASC, requestedAt DESC) in firestore.indexes.json, which
 *  must be READY before the Functions deploy). */
export const LIST_MAX = 500;
