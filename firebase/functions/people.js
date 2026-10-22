// D-197 (the Firebase plan's unit 7): finding people, worked out without
// touching Firebase, so firebase/tests/people.test.mjs runs every rule here
// directly. index.js reads and writes the documents.
//
// Three ways to find someone, all among people who let themselves be found
// (Settings, on by default): their @username, their exact email, or the
// emails in your contacts. Emails never reach the cloud as text from a
// searcher: the phone sends a SHA-256 of each (trimmed, lower case), and the
// cloud compares it with the fingerprint it keeps of each findable person's
// VERIFIED sign-in email, which only a Function writes.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

export const USERNAME = /^[a-z0-9_]{3,20}$/;
export const HASH = /^[0-9a-f]{64}$/;
export const MAX_HASHES = 500;
// D-229 decision 48: `aliases`, shareAliases' records (each costs the group's log plus every member's personal log;
// a phone asks once per (group, unaliased id), a handful ever, so 100 covers a large first repair: two full calls).
// D-230 decision 55: `places`, sharePlaces' records (one per (group, place, you) a phone ever holds; 100 covers two
// full calls).
// D-257 (G-04, G-23 lead (a)): per-GROUP kinds, charged on the counter document `limits/group-{groupId}` (an
// account's uid is alphanumeric, so it never names one). `groupInvites` 100: createInvite, inviteUser and inviteKnown
// each charge the group once (a group of 20 inviting 5 people each is 100; an honest group adds a handful a day).
// `groupJoins` 50: acceptInvite charges a join that happens (a whole new group of 50 joining in one day still fits).
// `groupRemovals` 100: removeMember and adoptRemovals charge a removal that happens (closes the remove, re-invite,
// rejoin cycle of lead (a), one removal record a cycle; an honest group removes a few people ever).
// D-257 (G-17): `pushes` 300, per AUTHOR account: onGroupRecord charges each record carrying a notice (an honest
// person adds a few dozen expenses a day at most); past it the record stays and no push goes.
export const LIMITS = {
  searches: 40, hashes: 2000, invites: 50, checks: 100, aliases: 100, places: 100,
  groupInvites: 100, groupJoins: 50, groupRemovals: 100, pushes: 300,
};

/** D-257: the counter document of group `groupId` (`limits/group-{groupId}`). */
export const groupLimitId = (groupId) => `group-${groupId}`;

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

/** C2: set once free, changed once, never removed. `first` is the
 *  required step's own save: it never spends the one change, so a username
 *  set meanwhile on another phone refuses it instead. */
export function usernameStep(old, next, changed, first = false) {
  if (next === old) return { same: true };
  if (next === null) return { error: 'A username can be changed, not removed.' };
  if (old === null) return { first: true };
  if (first) return { error: 'You already have a username.' };
  if (changed) return { error: 'Your username was already changed once.' };
  return { change: true };
}

/** C2: candidate handles from a name, best first: well formed, never
 *  blocked, no repeats. `year2` is the year's last two digits. */
export function suggestions(name, year2) {
  if (typeof name !== 'string') return [];
  const words = name.normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
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

/** The fingerprint of an email: SHA-256 hex of it trimmed and lower case. */
export function emailHash(email) {
  return createHash('sha256').update(email.trim().toLowerCase(), 'utf8').digest('hex');
}

/** The fingerprint a findable person's profile keeps: only for a verified
 *  sign-in email, and only while they are findable. */
export function profileHash(findable, token) {
  if (findable !== true) return null;
  if (typeof token?.email !== 'string' || !token.email.includes('@') || token.email_verified !== true) return null;
  return emailHash(token.email);
}

/** The search asked for: one username, or a list of fingerprints (deduplicated,
 *  at most MAX_HASHES); anything else is not a search. */
export function search(data) {
  const hasName = data?.username != null;
  const hasHashes = data?.emailHashes != null;
  if (hasName === hasHashes) return { error: 'one of username or emailHashes' };
  if (hasName) {
    const u = username(data.username);
    return u.error ? { error: 'username ' + u.error } : { username: u.handle };
  }
  if (!Array.isArray(data.emailHashes) || data.emailHashes.length === 0) return { error: 'emailHashes empty' };
  if (data.emailHashes.length > MAX_HASHES) return { error: 'too many emailHashes' };
  if (!data.emailHashes.every((h) => typeof h === 'string' && HASH.test(h))) return { error: 'emailHashes shape' };
  return { emailHashes: [...new Set(data.emailHashes)] };
}

/** One search result: who, as the app shows them; never their email. */
export function person(uid, profile, matched = null) {
  return {
    uid,
    name: typeof profile?.name === 'string' && profile.name.trim() ? profile.name.trim().slice(0, 60) : null,
    username: typeof profile?.username === 'string' ? profile.username : null,
    matched,
  };
}

/** Whether someone may be found: on unless they turned it off. */
export const findable = (profile) => profile?.findable !== false;

/** The daily counters after charging `kind` by `amount`, or null when that
 *  would pass today's limit. Counters reset each UTC day. */
export function charge(state, today, kind, amount = 1) {
  const fresh = state?.day === today ? { ...state } : { day: today, searches: 0, hashes: 0, invites: 0, checks: 0 };
  fresh[kind] = (fresh[kind] ?? 0) + amount;
  return fresh[kind] > LIMITS[kind] ? null : fresh;
}

export const utcDay = (ms) => new Date(ms).toISOString().slice(0, 10);

/** D-257 (G-17): a push of an author's record, charged on their counters.
 *  `send`: whether the push goes; `write`: the counters to store, or null
 *  for none; `log`: whether this is the first push held back today (logged
 *  once a day at most: `pushesCapped` marks the day, and goes with it). */
export function chargePush(state, today) {
  const next = charge(state, today, 'pushes');
  if (next) return { send: true, write: next, log: false };
  // Refused, so today's counter is full (charge refuses only on today's).
  if (state.pushesCapped === true) return { send: false, write: null, log: false };
  return { send: false, write: { ...state, pushesCapped: true }, log: true };
}

/** D-235 decision 121: what an in-app invite stores and sends, built from the
 *  server's names alone: `inviterName` (the inviter's profile name, or null)
 *  and `groupName` (groupNameOf). `fields` gives the ids and the member's
 *  name; its inviterName and groupName (the phone's) are never read. `link`
 *  is the invite document's names (peekInvite shows them), `copy` the
 *  in-app invitation (without its token and times), `push` the message. */
export function invitation(fields, inviterName, groupName) {
  const ids = { groupId: fields.groupId, memberId: fields.memberId, memberName: fields.memberName };
  return {
    link: { ...ids, groupName, inviterName },
    copy: { ...ids, groupName, fromName: inviterName },
    push: invitePush(inviterName, groupName),
  };
}

/** The push an in-app invite sends. */
export function invitePush(fromName, groupName) {
  const who = typeof fromName === 'string' && fromName.trim() ? fromName.trim() : 'Someone';
  return {
    notification: { title: 'SKIPTI', body: `${who} invited you to ${groupName}` },
    data: { kind: 'invite' },
    android: { priority: 'high', notification: { channelId: 'group-activity' } },
  };
}
