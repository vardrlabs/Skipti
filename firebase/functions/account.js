// D-198 (the Firebase plan's unit 8): your phones and deleting your account,
// worked out without touching Firebase, so firebase/tests/account.test.mjs
// runs every rule here directly. account-store.js does the deleting;
// index.js reads, writes and sends.
//
// SPEC §4.4: deleting an account is one request, a 7-day grace in which
// signing in again keeps it, then irreversible. Groups where you are the last
// member are destroyed; groups with others survive without you.

export const GRACE_DAYS = 7;
const DAY = 24 * 3600 * 1000;
const HEX32 = /^[0-9a-f]{32}$/;

/** When a deletion asked for at `nowMs` happens. */
export const deletionAt = (nowMs) => nowMs + GRACE_DAYS * DAY;

/** Whether an account asked to be deleted is due at `nowMs`. */
export function due(profile, nowMs) {
  const at = profile?.deleteAt;
  const ms = typeof at?.toMillis === 'function' ? at.toMillis() : typeof at === 'number' ? at : null;
  return ms != null && ms <= nowMs;
}

/** A phone of the account, named by the id the rules allow. */
export const deviceId = (value) => typeof value === 'string' && HEX32.test(value);

/** What deleting `uid` does to its groups: leave those with other members;
 *  destroy those where it is the last one. A group it owns that others remain
 *  in is handed on (D-210): to the first admin in join order, else the first
 *  member left. `groups` is [{id, memberUids, owner?, createdBy?, admins?}]. */
export function groupsPlan(groups, uid) {
  const leave = [];
  const destroy = [];
  const handOver = [];
  for (const g of groups ?? []) {
    const members = Array.isArray(g?.memberUids) ? g.memberUids : [];
    if (!members.includes(uid)) continue;
    const rest = members.filter((m) => m !== uid);
    if (!rest.length) { destroy.push(g.id); continue; }
    leave.push(g.id);
    const owner = typeof g.owner === 'string' ? g.owner : g.createdBy;
    if (owner !== uid) continue;
    const admins = Array.isArray(g.admins) ? g.admins : [];
    handOver.push({ id: g.id, owner: rest.find((m) => admins.includes(m)) ?? rest[0] });
  }
  return { leave, destroy, handOver };
}

/** The phones a new phone's sign-in is told to: the account's others with a
 *  push address, not signed out. `phones` is [{id, data}]. */
export function otherPhones(phones, newId) {
  return (phones ?? [])
    .filter((p) => p?.id !== newId && p?.data?.signedOut !== true && typeof p?.data?.fcmToken === 'string' && p.data.fcmToken)
    .map((p) => ({ token: p.data.fcmToken, id: p.id }));
}

/** The push a new phone's sign-in sends. */
export function devicePush(name) {
  const phone = typeof name === 'string' && name.trim() ? name.trim().slice(0, 60) : 'A new phone';
  return {
    notification: { title: 'SKIPTI', body: `${phone} signed in to your account. Was this you?` },
    data: { kind: 'device' },
    android: { priority: 'high', notification: { channelId: 'group-activity' } },
  };
}
