// D-210 (C3): who may do what in a group, worked out without touching
// Firebase, so firebase/tests/groups.test.mjs runs every rule here directly.
// index.js reads the documents, asks these, and writes.

const HEX32 = /^[0-9a-f]{32}$/;
const MAX_RECORDS = 11; // one removeMember + at most ten currencies
const MAX_BLOB = 65536; // base64 characters
const MAX_NONCE = 64;
const DAY_MS = 24 * 3600 * 1000;
const BASE64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

/** The group's owner: `owner`, or `createdBy` when it is absent. One no
 *  longer in the group (a creator purged before D-210 handed groups on)
 *  leaves the seat to the first admin in member order, else the first
 *  member. Neither recorded is nobody. */
export function ownerOf(group) {
  const recorded = typeof group?.owner === 'string' ? group.owner
    : typeof group?.createdBy === 'string' ? group.createdBy : null;
  if (recorded === null) return null;
  const list = members(group);
  if (list.includes(recorded)) return recorded;
  const admins = adminsOf(group);
  return list.find((u) => admins.includes(u)) ?? list[0] ?? null;
}

export function adminsOf(group) {
  return Array.isArray(group?.admins) ? group.admins.filter((u) => typeof u === 'string') : [];
}

export function isAdmin(group, uid) {
  return adminsOf(group).includes(uid);
}

function members(group) {
  return Array.isArray(group?.memberUids) ? group.memberUids : [];
}

/** `target` is a uid, or null for someone with no account. */
export function can(action, group, caller, target) {
  const inGroup = (u) => members(group).includes(u);
  if (!inGroup(caller)) return false;
  const owner = ownerOf(group);
  const isOwner = owner !== null && caller === owner;
  // A group with no owner recorded has nobody in that seat: a null target
  // (someone with no account) is not "the owner" then.
  const isTheOwner = (u) => owner !== null && u === owner;
  const manages = isOwner || isAdmin(group, caller);
  switch (action) {
    // A no-op promotion is refused, so the Function can say so.
    case 'admin-on': return manages && target !== null && inGroup(target) && !isTheOwner(target) && !isAdmin(group, target);
    case 'admin-off': return isOwner && isAdmin(group, target);
    case 'owner': return isOwner && target !== null && target !== caller && inGroup(target);
    case 'request': return !isTheOwner(target);
    case 'remove':
    case 'decline':
      if (isTheOwner(target)) return false;
      if (target !== null && isAdmin(group, target)) return isOwner;
      return manages;
    case 'delete': return isOwner;
    default: return false;
  }
}

export function json(base64) {
  try { return JSON.parse(Buffer.from(base64, 'base64').toString('utf8')); } catch { return null; }
}

const b64 = (v, max) => typeof v === 'string' && v.length > 0 && v.length <= max && BASE64.test(v);

function recordShape(r) {
  const h = r?.hlc;
  return r && typeof r === 'object'
    // D-235 round 2 (m1): decision 105 holds for what the Functions write
    // too: a stamp at most 24 hours past the server's clock.
    && Number.isSafeInteger(h?.millis) && h.millis >= 0 && h.millis <= Date.now() + DAY_MS
    && Number.isSafeInteger(h?.counter) && h.counter >= 0
    && HEX32.test(h?.device ?? '')
    && r.id === `${h.millis}-${h.counter}-${h.device}`
    && HEX32.test(r.deviceId ?? '')
    && r.payload && typeof r.payload === 'object' && b64(r.payload.blob, MAX_BLOB) && b64(r.payload.nonce, MAX_NONCE)
    // D-235: version 1, core's RECORD_HEADER_VERSION: a phone's vault refuses
    // any other, so a marked record of another version would stall its sync.
    && r.payload.version === 1 && Number.isSafeInteger(r.payload.epoch) && Number.isSafeInteger(r.payload.flags);
}

/** null when the records are exactly one removeMember of `member` in `groupId`
 *  plus that person's redistributions; otherwise 'records'. */
export function checkRemovalRecords(records, groupId, member) {
  if (!Array.isArray(records) || records.length < 1 || records.length > MAX_RECORDS) return 'records';
  let removals = 0;
  const ids = new Set();
  for (const r of records) {
    if (!recordShape(r) || ids.has(r.id)) return 'records';
    ids.add(r.id);
    const blob = json(r.payload.blob);
    if (r.type === '3') {
      if (blob?.op !== 'removeMember' || blob.group !== groupId || blob.member !== member) return 'records';
      removals += 1;
    } else if (r.type === '6') {
      if (blob?.group !== groupId || blob.from !== member) return 'records';
    } else {
      return 'records';
    }
  }
  return removals === 1 ? null : 'records';
}

const MAX_ADOPTED = 50; // adoptRemovals: what one call writes

/** null when `records` (1 to 50) are each a removeMember op or a
 *  redistribution (type 6) of `groupId`, of anyone, well formed, with no id
 *  twice; otherwise 'records'. adoptRemovals (D-210 final review I4): the
 *  removals a phone made while the group was only on it. */
export function checkAdoptedRecords(records, groupId) {
  if (!Array.isArray(records) || records.length < 1 || records.length > MAX_ADOPTED) return 'records';
  const ids = new Set();
  for (const r of records) {
    if (!recordShape(r) || ids.has(r.id)) return 'records';
    ids.add(r.id);
    const blob = json(r.payload.blob);
    if (r.type === '3') {
      if (blob?.op !== 'removeMember' || blob.group !== groupId || !HEX32.test(blob.member ?? '')) return 'records';
    } else if (r.type === '6') {
      if (blob?.group !== groupId || !HEX32.test(blob.from ?? '')) return 'records';
    } else {
      return 'records';
    }
  }
  return null;
}

const MAX_PROFILES = 50; // shareProfile: one record per group
const JPEG_HEX = /^ffd8ff([0-9a-f]{2})*$/;
const THUMB_MAX_BYTES = 12000;
const NAME_MAX_CHARS = 64; // core's MEMBER_NAME_MAX_CHARS
// Rust's char::is_control (general category Cc) and str::trim (the Unicode
// White_Space property): JS's own trim differs (it drops U+FEFF, which core
// keeps), so neither is used. A lone surrogate never decodes in core.
const CONTROL = /\p{Cc}/u;
const OUTER_SPACE = /^\p{White_Space}|\p{White_Space}$/u;
const LONE_SURROGATE = /\p{Cs}/u;

/** Text exactly as core stores it: trimmed, 1 to `maxChars` characters (code
 *  points), at most `maxBytes` UTF-8 bytes, no control characters. */
function coreText(v, maxChars, maxBytes = Infinity) {
  if (typeof v !== 'string' || LONE_SURROGATE.test(v)) return false;
  const n = [...v].length;
  return n >= 1 && n <= maxChars && Buffer.byteLength(v, 'utf8') <= maxBytes
    && !OUTER_SPACE.test(v) && !CONTROL.test(v);
}

// D-235 (Android audit H2): the largest member preview a phone may be asked
// to draw, a side and in all, as core's THUMB_MAX_SIDE / THUMB_MAX_PIXELS
// (wave A; the shells make 120 by 120). A JPEG of a few KB can declare 7000
// by 7000 in its frame header and crash every phone that decodes it whole.
export const THUMB_MAX_SIDE = 512;
export const THUMB_MAX_PIXELS = 512 * 512;

/** Every frame size a JPEG's headers declare before its first scan, read as
 *  core's jpeg_frame_sizes reads them (libjpeg's way): bytes between markers
 *  are skipped up to the next FF, FF fill bytes are skipped, a marker with no
 *  length (RSTn, TEM, a stuffed 00, SOI) has none to skip, and every SOFn (C0
 *  to CF but DHT C4, JPG C8, DAC CC) and DHP (DE) gives its width and height.
 *  Stops at the first SOS (DA) or EOI (D9). Nothing is decoded. */
export function jpegFrameSizes(buf) {
  const sizes = [];
  if (!Buffer.isBuffer(buf)) return sizes;
  let i = 2;
  for (;;) {
    while (i < buf.length && buf[i] !== 0xff) i += 1;
    while (i < buf.length && buf[i] === 0xff) i += 1;
    if (i >= buf.length) return sizes;
    const marker = buf[i];
    i += 1;
    if (marker === 0x00 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd8)) continue;
    if (marker === 0xd9 || marker === 0xda) return sizes;
    if (i + 2 > buf.length) return sizes;
    const len = buf.readUInt16BE(i);
    const frame = (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) || marker === 0xde;
    if (frame && i + 7 <= buf.length) sizes.push({ width: buf.readUInt16BE(i + 5), height: buf.readUInt16BE(i + 3) });
    if (len < 2) return sizes;
    i += len;
  }
}

/** Whether every declared frame size (jpegFrameSizes) is 1 to `maxSide` a
 *  side and at most `maxPixels` in all, as core's check_jpeg_hex. A file that
 *  declares none passes, as in core: no decoder can size it, so none draws it. */
export function frameSizesOk(sizes, maxSide = THUMB_MAX_SIDE, maxPixels = THUMB_MAX_PIXELS) {
  return sizes.every(({ width, height }) => width >= 1 && height >= 1 && width <= maxSide && height <= maxSide
    && width * height <= maxPixels);
}

/** Whether `v` is a group name core keeps (check_group_name: not empty, no
 *  white space at either end, by Unicode's White_Space as Rust's trim). */
function coreGroupName(v) {
  return typeof v === 'string' && v.length > 0 && !LONE_SURROGATE.test(v) && !OUTER_SPACE.test(v);
}
/** What a notice shows of a name core keeps: its first 80 characters (code
 *  points), any white space the cut leaves at the end dropped. */
const shown = (v) => [...v].slice(0, 80).join('').replace(/\p{White_Space}+$/u, '');

/** D-235 (Firebase audit M2): the group's name as the server reads it from
 *  the group's own log (`events` from index.js opsOf): the name of its latest
 *  create or renameGroup op by HLC, which is the one every phone shows (core
 *  takes the latest write; round 2 m2: a name core would refuse, such as one
 *  with outer spaces, is skipped as core skips it, never trimmed into one);
 *  else the group document's name (`fallback`, set when the group first
 *  reached the cloud and never renamed there); else "Group". Notices carry
 *  it, never a name the phone sends. At most 80 characters. */
export function groupNameOf(events, fallback) {
  let best = null;
  for (const e of Array.isArray(events) ? events : []) {
    const op = e?.blob;
    if ((op?.op !== 'create' && op?.op !== 'renameGroup') || !coreGroupName(op.name)) continue;
    if (best === null || hlcOrder(e.hlc, best.hlc) > 0) best = { hlc: e.hlc, name: op.name };
  }
  if (best) return shown(best.name);
  return typeof fallback === 'string' && fallback.trim() ? shown(fallback.trim()) : 'Group';
}

const HEX = (v) => typeof v === 'string' && HEX32.test(v);
// D-235 round 3 (R2-2): the most members of one op the server reads. Core has
// no such cap (check_member checks each one); an honest create names the
// group's first people, and 500 is far past any real group. Members past it
// are not read (a place only they name reads as never made).
export const MEMBERS_MAX = 500;

/** D-235 round 2 (C1, I3): the part of a group op the Functions read
 *  (logOf, creatorPlaceOf, removedSince, groupNameOf), and nothing else: the
 *  op's name and group; `member` (an id, or {id} for addMember); `members`
 *  as [{id}] in their places (an id that is not 32 hex is null, so the first
 *  member stays the first), at most MEMBERS_MAX of them; `you`; and a `name` core keeps, cut to what a
 *  notice shows. Pictures, devices and every other field are dropped. */
export function compactOp(op) {
  const out = { op: typeof op?.op === 'string' ? op.op.slice(0, 40) : null, group: op?.group };
  if (HEX(op?.member)) out.member = op.member;
  else if (HEX(op?.member?.id)) out.member = { id: op.member.id };
  if (Array.isArray(op?.members)) out.members = op.members.slice(0, MEMBERS_MAX).map((m) => ({ id: HEX(m?.id) ? m.id : null }));
  if (HEX(op?.you)) out.you = op.you;
  if (coreGroupName(op?.name)) out.name = shown(op.name);
  return out;
}

/** D-235 round 3 (R2-2): a generous estimate of the heap a compact op
 *  (compactOp) takes in V8: about 300 bytes for the event, its stamp and
 *  strings, two bytes a character of its name, and 120 bytes a member (an
 *  object, its slot and a 32-character string). What opsOf counts against an
 *  author's OPS_BYTES_PER_AUTHOR before it keeps the op. */
export function opBytes(c) {
  return 300 + 2 * (c?.name?.length ?? 0) + 120 * (c?.members?.length ?? 0);
}

/** null when `records` (1 to 50) are each a memberProfile of a distinct
 *  group, well formed, named `name`, with no id twice; otherwise 'records'
 *  or 'name'. The place (`member`) is decided per group by the caller. The
 *  name and the emoji pass core's own checks (check_member_name,
 *  check_emoji), so nothing core would refuse is written. */
export function checkProfileRecords(records, name) {
  if (!Array.isArray(records) || records.length < 1 || records.length > MAX_PROFILES) return 'records';
  const ids = new Set();
  const seenGroups = new Set();
  for (const r of records) {
    if (!recordShape(r) || ids.has(r.id) || r.type !== '3') return 'records';
    ids.add(r.id);
    const b = json(r.payload.blob);
    if (b?.op !== 'memberProfile' || !HEX32.test(b.group ?? '') || !HEX32.test(b.member ?? '')) return 'records';
    if (seenGroups.has(b.group)) return 'records';
    seenGroups.add(b.group);
    if (!coreText(b.name, NAME_MAX_CHARS)) return 'records';
    if (b.emoji != null && b.thumb != null) return 'records';
    if (b.thumb != null && (typeof b.thumb !== 'string' || b.thumb.length / 2 > THUMB_MAX_BYTES || !JPEG_HEX.test(b.thumb))) return 'records';
    if (b.thumb != null && !frameSizesOk(jpegFrameSizes(Buffer.from(b.thumb, 'hex')))) return 'records';
    if (b.emoji != null && !coreText(b.emoji, 8, 32)) return 'records';
    const extra = Object.keys(b).filter((k) => !['op', 'group', 'member', 'name', 'emoji', 'thumb'].includes(k));
    if (extra.length) return 'records';
  }
  for (const r of records) if (json(r.payload.blob).name !== name) return 'name';
  return null;
}

const MAX_ALIASES = 50; // shareAliases: what one call asks

/** D-229: null when `records` (1 to 50) are each a placeAlias op (type 3)
 *  with exactly `op`, `group`, `member` and `you`, three 32-hex ids, `you`
 *  not the place it reads as, well formed, with no record id twice and no
 *  (group, you) twice (one id reads as one place in a group); otherwise
 *  'records'. Whether the place and the id are the caller's is decided per
 *  record by shareAliases, never here. */
export function checkAliasRecords(records) {
  if (!Array.isArray(records) || records.length < 1 || records.length > MAX_ALIASES) return 'records';
  const ids = new Set();
  const pairs = new Set();
  for (const r of records) {
    if (!recordShape(r) || ids.has(r.id) || r.type !== '3') return 'records';
    ids.add(r.id);
    const b = json(r.payload.blob);
    if (b?.op !== 'placeAlias') return 'records';
    if (Object.keys(b).some((k) => !['op', 'group', 'member', 'you'].includes(k))) return 'records';
    if (![b.group, b.member, b.you].every((v) => typeof v === 'string' && HEX32.test(v))) return 'records';
    if (b.you === b.member) return 'records';
    const pair = `${b.group}:${b.you}`;
    if (pairs.has(pair)) return 'records';
    pairs.add(pair);
  }
  return null;
}

const MAX_HELD = 50; // sharePlaces: what one call asks
export const MAX_JOIN_HELD = 11; // acceptInvite: the new place and at most ten old ones (D-230 A1)

/** D-230: null when `records` (1 to `max`: 50 for sharePlaces, 11 at the
 *  join) are each a heldPlace op (type 3) with exactly `op`, `group`,
 *  `member` and `you`, three 32-hex ids (`you` MAY equal `member`: a
 *  creator's raw place), well formed, no record id twice and no (group,
 *  member, you) twice (A5: a two-phone account marks one member once per id,
 *  so one member with two ids is valid); otherwise 'records'. Whether the
 *  caller holds the place and owns the id is decided per record by the
 *  Functions, never here. */
export function checkHeldRecords(records, max = MAX_HELD) {
  if (!Array.isArray(records) || records.length < 1 || records.length > max) return 'records';
  const ids = new Set();
  const triples = new Set();
  for (const r of records) {
    if (!recordShape(r) || ids.has(r.id) || r.type !== '3') return 'records';
    ids.add(r.id);
    const b = json(r.payload.blob);
    if (b?.op !== 'heldPlace') return 'records';
    if (Object.keys(b).some((k) => !['op', 'group', 'member', 'you'].includes(k))) return 'records';
    if (![b.group, b.member, b.you].every((v) => typeof v === 'string' && HEX32.test(v))) return 'records';
    const triple = `${b.group}:${b.member}:${b.you}`;
    if (triples.has(triple)) return 'records';
    triples.add(triple);
  }
  return null;
}

/** Orders two record HLCs ({millis, counter, device}) as core's Hlc does:
 *  millis, then counter, then the device id (lowercase hex compares as its
 *  bytes). Negative, zero or positive. A malformed one is the oldest. */
export function hlcOrder(a, b) {
  const ok = (h) => Number.isSafeInteger(h?.millis) && Number.isSafeInteger(h?.counter) && typeof h?.device === 'string';
  if (!ok(a) || !ok(b)) return (ok(a) ? 1 : 0) - (ok(b) ? 1 : 0);
  if (a.millis !== b.millis) return a.millis < b.millis ? -1 : 1;
  if (a.counter !== b.counter) return a.counter < b.counter ? -1 : 1;
  return a.device === b.device ? 0 : a.device < b.device ? -1 : 1;
}

/** D-227: the place a group's creator made for itself: the first member of
 *  the earliest `create` op that account wrote (`createdBy`), or null. `ops`
 *  are this group's parsed group ops, {authorUid, createdAt (server millis),
 *  hlc, blob}. Earliest by the server's createdAt, then the HLC (hlcOrder): a
 *  phone chooses its own clock, never the server's time. Only the creator's own
 *  creates count (the rules set authorUid to the writer), so another member's
 *  create, however early, is ignored, and a later create of the creator's never
 *  moves the place. The earliest's first member malformed is null, never the
 *  next create's. Core's create_named_group puts the phone's own "you" first. */
export function creatorPlaceOf(ops, createdBy) {
  if (typeof createdBy !== 'string' || !createdBy) return null;
  let first = null;
  for (const o of Array.isArray(ops) ? ops : []) {
    if (o?.blob?.op !== 'create' || o.authorUid !== createdBy) continue;
    const earlier = first === null || (o.createdAt !== first.createdAt ? o.createdAt < first.createdAt : hlcOrder(o.hlc, first.hlc) < 0);
    if (earlier) first = o;
  }
  const id = Array.isArray(first?.blob?.members) ? first.blob.members[0]?.id : null;
  return typeof id === 'string' && HEX32.test(id) ? id : null;
}

/** Founder decision 5 (D-211): of an account's marked profiles in a group
 *  (`mine`: [{id, hlc}]) and the record it shares now (`r`), the one that
 *  stays is the latest by HLC, which is the one every phone's projection
 *  shows. Returns { keep: id, write: whether `r` is written, drop: [ids] }.
 *  `r` already stored (a retry) is not written again. */
export function profilePlan(mine, r) {
  const all = [...mine.filter((d) => d.id !== r.id), { id: r.id, hlc: r.hlc }];
  const keep = all.reduce((best, d) => (hlcOrder(d.hlc, best.hlc) > 0 ? d : best)).id;
  return {
    keep,
    write: keep === r.id && !mine.some((d) => d.id === r.id),
    drop: mine.filter((d) => d.id !== keep).map((d) => d.id),
  };
}

/** Whether place `member` stands removed: the latest `removal: true`
 *  removeMember of it is newer than the latest addMember of it. `events` are
 *  {createdAt (server millis), removal, blob (the parsed group op)}. Server
 *  time decides, because a phone chooses its own HLC. A place never added in
 *  this log but removed stands removed. */
export function removedSince(events, member) {
  let removedAt = -Infinity;
  let addedAt = -Infinity;
  for (const e of events) {
    const op = e?.blob;
    if (op?.op === 'removeMember' && op.member === member && e.removal === true) removedAt = Math.max(removedAt, e.createdAt);
    if (op?.op === 'addMember' && op.member?.id === member) addedAt = Math.max(addedAt, e.createdAt);
  }
  return removedAt > addedAt;
}

/** Which account joined a place, from the server's own record: the place's
 *  invites (invites/{hash} with its groupId and memberId), whose `usedBy`
 *  acceptInvite sets. Only accounts still in `memberUids` count. One: that
 *  uid; none: null; two or more different ones: contested. A phone's claim op
 *  is never consulted: any member can write one about any place. */
export function joinedAs(invites, memberUids) {
  const members = Array.isArray(memberUids) ? memberUids : [];
  const uids = new Set();
  for (const i of invites ?? []) {
    const u = i?.usedBy;
    // An invite that ended (removeMember, `endedAt`) joined its account to a
    // place it was removed from: it names that place no more.
    if (i?.endedAt) continue;
    if (typeof u === 'string' && u && members.includes(u)) uids.add(u);
  }
  if (uids.size > 1) return { uid: null, contested: true };
  return { uid: uids.size ? [...uids][0] : null, contested: false };
}

/** D-218: whether a place is held, for the invite Functions: `creatorUids`
 *  is the group's creator when it holds the place as its creator place (D-227,
 *  index.js creatorHolder: a member, and no removal ever written of the place),
 *  else empty; `invited` is what joinedAs says of its invites. Held when the
 *  creator holds it, an accepted invite of a member joined it, or two accounts
 *  did (contested is held, not an error: the Function only refuses). Malformed
 *  input holds nothing. */
export function placeHeld(creatorUids, invited) {
  const creator = Array.isArray(creatorUids) ? creatorUids : [];
  return creator.length > 0 || Boolean(invited?.uid) || invited?.contested === true;
}

/** D-252 (decision 218): who is told that a group was deleted: every member,
 *  the deleting account included (its other phones learn it only from this
 *  notice), once each; the deleting account is `quiet`: its notice is stored
 *  for its phones to read, and none of them is pushed (the phone that deleted
 *  already shows it). */
export function deletionAudience(memberUids, caller) {
  const members = Array.isArray(memberUids) ? memberUids.filter((u) => typeof u === 'string' && u) : [];
  const told = [...new Set(typeof caller === 'string' && caller ? [...members, caller] : members)];
  return { told, quiet: typeof caller === 'string' && caller ? [caller] : [] };
}

export function noticeBody(kind, n) {
  switch (kind) {
    case 'request': return n.toPerson ? `${n.byName} asked to remove you` : `${n.byName} asked to remove ${n.memberName}`;
    case 'declined': return n.toPerson ? `${n.byName} kept you in ${n.groupName}` : `${n.byName} kept ${n.memberName} in ${n.groupName}`;
    case 'removed': return `${n.byName} removed you from ${n.groupName}`;
    case 'role': return n.role === 'owner' ? `${n.byName} made you the owner of ${n.groupName}` : `${n.byName} made you an admin of ${n.groupName}`;
    case 'deleted': return `${n.byName} deleted ${n.groupName}`;
    default: throw new Error('unknown notice');
  }
}

export function noticePush(n) {
  if (!HEX32.test(n?.groupId ?? '')) throw new Error('groupId');
  return {
    notification: { title: n.groupName || 'SKIPTI', body: noticeBody(n.kind, n) },
    data: { kind: 'group', groupId: n.groupId },
    android: { priority: 'high', notification: { channelId: 'group-activity', tag: n.groupId } },
  };
}

export function requestNoticeId(groupId, member) {
  return `request-${groupId}-${member}`;
}
