// D-196 (the Firebase plan's unit 6): what onGroupRecord sends, worked out
// without touching Firebase, so firebase/tests/push.test.mjs runs every rule
// here directly. index.js only reads the documents and hands them over.
//
// A group expense record carries `notice` {text, expense}: the line its
// author's phone wrote ("Rahul added Dinner · ₹1,200"), and the expense a
// tap opens. It goes to every OTHER member's phones, titled with the group's
// name. Nothing else about the record leaves the cloud in a push.

export const MAX_TOKENS = 500; // FCM's limit for one multicast
const EXPENSE = /^[0-9]{1,20}:[0-9]{1,10}:[0-9a-f]{32}$/;
const HEX32 = /^[0-9a-f]{32}$/;

/** The notice, checked again (the rules already did), or null. */
export function notice(record) {
  const n = record?.notice;
  if (!n || typeof n !== 'object') return null;
  if (typeof n.text !== 'string' || !n.text.trim() || n.text.length > 200) return null;
  if (typeof n.expense !== 'string' || !EXPENSE.test(n.expense)) return null;
  return { text: n.text.trim(), expense: n.expense };
}

/** Who is told: the group's members except the author; nobody when the
 *  author is not a member (the rules refuse that write; checked anyway). */
export function recipients(group, authorUid) {
  const members = Array.isArray(group?.memberUids) ? group.memberUids.filter((u) => typeof u === 'string') : [];
  if (typeof authorUid !== 'string' || !members.includes(authorUid)) return [];
  return [...new Set(members.filter((u) => u !== authorUid))];
}

/** D-252: whose phones a notice is pushed to: everyone told, once each, less
 *  the `quiet` accounts (the caller's own: its phones already know). A quiet
 *  account still gets the notice stored; only the push is left out. */
export function loud(uids, quiet = []) {
  const still = new Set((Array.isArray(quiet) ? quiet : []).filter((u) => typeof u === 'string'));
  return [...new Set((uids ?? []).filter((u) => typeof u === 'string' && u && !still.has(u)))];
}

/** The push addresses of a member's phones: each phone that has one and is
 *  not signed out, once each, at most MAX_TOKENS in all. `devices` is a list
 *  of {uid, id, data}. Returns [{token, uid, id}]. */
export function tokens(devices) {
  const seen = new Set();
  const out = [];
  for (const d of devices ?? []) {
    const t = d?.data?.fcmToken;
    if (typeof t !== 'string' || !t || t.length > 4096 || d.data.signedOut === true || seen.has(t)) continue;
    seen.add(t);
    out.push({ token: t, uid: d.uid, id: d.id });
    if (out.length === MAX_TOKENS) break;
  }
  return out;
}

/** The message, without its tokens. Title: the group's name; body: the
 *  notice; data: where a tap goes. iOS threads a group's pushes together,
 *  Android posts them on the "Group activity" channel. */
export function message(n, groupId, groupName) {
  const title = typeof groupName === 'string' && groupName.trim() ? groupName.trim().slice(0, 80) : 'SKIPTI';
  if (!HEX32.test(groupId)) throw new Error('groupId is not 32 hex');
  return {
    notification: { title, body: n.text },
    data: { kind: 'record', groupId, expense: n.expense },
    apns: { payload: { aps: { 'thread-id': groupId, sound: 'default' } } },
    android: { priority: 'high', notification: { channelId: 'group-activity', tag: n.expense } },
  };
}

// The errors that mean a token will never work again. Anything else
// (quota, a timeout, a bad payload) leaves the token where it is.
const DEAD = new Set(['messaging/registration-token-not-registered', 'messaging/invalid-registration-token']);

const CODE = /^messaging\/[a-z-]{1,60}$/;

/** D-204: how a send went, for the log: counts and FCM's error codes, never
 *  a token. sendEachForMulticast reports a refused message in its response
 *  rather than throwing, so without this every refusal is silent. */
export function outcome(response) {
  const out = { delivered: 0, failed: 0, errors: {} };
  for (const r of response?.responses ?? []) {
    if (r?.success) {
      out.delivered += 1;
      continue;
    }
    out.failed += 1;
    const code = typeof r?.error?.code === 'string' && CODE.test(r.error.code) ? r.error.code : 'other';
    out.errors[code] = (out.errors[code] ?? 0) + 1;
  }
  return out;
}

/** The addresses FCM said are gone, from sendEachForMulticast's response. */
export function deadTokens(sent, response) {
  const out = [];
  (response?.responses ?? []).forEach((r, i) => {
    if (!r?.success && DEAD.has(r?.error?.code) && sent[i]) out.push(sent[i]);
  });
  return out;
}
