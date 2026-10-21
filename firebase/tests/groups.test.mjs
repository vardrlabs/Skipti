// D-210 (C3): the cloud's role decisions (firebase/functions/groups.js) and the
// purge hand-over (account.js groupsPlan), tested directly. No emulator needed.
import test from 'node:test';
import assert from 'node:assert/strict';
import * as groups from '../functions/groups.js';
import * as account from '../functions/account.js';

const g = (over = {}) => ({ memberUids: ['ow', 'ad', 'me', 'm2'], createdBy: 'ow', ...over });

test('the owner is `owner`, else whoever made the group', () => {
  assert.equal(groups.ownerOf(g()), 'ow');
  assert.equal(groups.ownerOf(g({ owner: 'ad' })), 'ad');
  assert.equal(groups.ownerOf({ memberUids: ['x'] }), null);
});

test('who may do what (the founder\'s rules, 2026-10-13)', () => {
  const x = g({ admins: ['ad'] });
  // Admins: owner or admin names one; only the owner takes it away.
  assert.equal(groups.can('admin-on', x, 'ow', 'me'), true);
  assert.equal(groups.can('admin-on', x, 'ad', 'me'), true);
  assert.equal(groups.can('admin-on', x, 'me', 'm2'), false);
  assert.equal(groups.can('admin-off', x, 'ow', 'ad'), true);
  assert.equal(groups.can('admin-off', x, 'ad', 'ad'), false);
  assert.equal(groups.can('admin-on', x, 'ow', 'stranger'), false);
  // One owner; only the owner hands over, and only to a member.
  assert.equal(groups.can('owner', x, 'ow', 'me'), true);
  assert.equal(groups.can('owner', x, 'ad', 'me'), false);
  assert.equal(groups.can('owner', x, 'ow', 'ow'), false);
  // Removing: never the owner; admins remove members, not admins.
  assert.equal(groups.can('remove', x, 'ad', 'me'), true);
  assert.equal(groups.can('remove', x, 'ad', null), true);
  assert.equal(groups.can('remove', x, 'ad', 'ow'), false);
  assert.equal(groups.can('remove', x, 'ow', 'ad'), true);
  assert.equal(groups.can('remove', g({ admins: ['ad', 'me'] }), 'ad', 'me'), false);
  assert.equal(groups.can('remove', x, 'me', 'm2'), false);
  // Asking: any member, about anyone but the owner, themselves included.
  assert.equal(groups.can('request', x, 'me', 'me'), true);
  assert.equal(groups.can('request', x, 'me', null), true);
  assert.equal(groups.can('request', x, 'me', 'ow'), false);
  assert.equal(groups.can('request', x, 'stranger', 'me'), false);
  // Declining follows removing.
  assert.equal(groups.can('decline', x, 'ad', 'me'), true);
  assert.equal(groups.can('decline', x, 'ad', 'ad'), false);
  assert.equal(groups.can('decline', x, 'me', 'm2'), false);
  // Deleting: the owner alone.
  assert.equal(groups.can('delete', x, 'ow', null), true);
  assert.equal(groups.can('delete', x, 'ad', null), false);
  assert.equal(groups.can('unknown', x, 'ow', null), false);
});

const G = 'c'.repeat(32), M = 'd'.repeat(32), D = 'e'.repeat(32);
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64');
const rec = (type, blob, n = 1) => ({
  id: `${1000 + n}-0-${D}`, type, hlc: { millis: 1000 + n, counter: 0, device: D }, deviceId: D,
  payload: { version: 1, epoch: 0, flags: 0, nonce: Buffer.from('RED\x01').toString('base64'), blob: b64(blob) },
});
const removal = rec('3', { op: 'removeMember', group: G, member: M }, 1);
const moved = rec('6', { group: G, from: M, currency: 'INR', shares: [{ member: 'a'.repeat(32), amount: -1 }] }, 2);

test('a removal carries exactly one removeMember for that person and only their redistributions', () => {
  assert.equal(groups.checkRemovalRecords([removal], G, M), null);
  assert.equal(groups.checkRemovalRecords([moved, removal], G, M), null);
  assert.equal(groups.checkRemovalRecords([], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([moved], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([removal, removal], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([rec('3', { op: 'removeMember', group: G, member: D })], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([rec('3', { op: 'renameGroup', group: G, name: 'x' })], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([removal, rec('6', { group: G, from: D, currency: 'INR', shares: [] }, 2)], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([removal, rec('1', {}, 2)], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([{ ...removal, id: 'x' }], G, M), 'records');
  assert.equal(groups.checkRemovalRecords(Array.from({ length: 12 }, (_, i) => (i ? rec('6', { group: G, from: M, currency: 'INR', shares: [] }, i + 2) : removal)), G, M), 'records');
});

test('D-210 review: an unset owner is nobody; a no-op promotion is refused', () => {
  const noOwner = { memberUids: ['ad', 'me', 'm2'], admins: ['ad'] };
  assert.equal(groups.ownerOf(noOwner), null);
  // A null target (someone with no account) is not "the owner" when there is none.
  assert.equal(groups.can('remove', noOwner, 'ad', null), true);
  assert.equal(groups.can('decline', noOwner, 'ad', null), true);
  assert.equal(groups.can('request', noOwner, 'me', null), true);
  assert.equal(groups.can('remove', noOwner, 'me', null), false, 'a plain member still cannot');
  // Making an admin of someone who already is one is refused, so the Function can say so.
  const x = g({ admins: ['ad', 'me'] });
  assert.equal(groups.can('admin-on', x, 'ow', 'me'), false);
  assert.equal(groups.can('admin-on', x, 'ad', 'm2'), true);
});

test('D-210 review: removal records are bounded, well-formed base64 and JSON, and complete', () => {
  assert.equal(groups.checkRemovalRecords([{ ...removal, payload: { ...removal.payload, blob: 'not base64!' } }], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([{ ...removal, payload: { ...removal.payload, blob: Buffer.from('{not json').toString('base64') } }], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([{ ...removal, payload: { ...removal.payload, nonce: '%%%' } }], G, M), 'records');
  for (const field of ['version', 'epoch', 'flags', 'nonce', 'blob']) {
    const payload = { ...removal.payload };
    delete payload[field];
    assert.equal(groups.checkRemovalRecords([{ ...removal, payload }], G, M), 'records', `no ${field}`);
  }
  // D-235: version 1 only (core's RECORD_HEADER_VERSION; a vault refuses any other).
  for (const version of [0, 2, 65535]) {
    assert.equal(groups.checkRemovalRecords([{ ...removal, payload: { ...removal.payload, version } }], G, M), 'records', `version ${version}`);
  }
  // Sizes: a blob of at most 65536 base64 characters, a nonce of at most 64.
  const padBlob = (chars) => {
    const json = JSON.stringify({ op: 'removeMember', group: G, member: M, pad: '' });
    const room = Math.floor(chars / 4) * 3 - json.length;
    const blob = Buffer.from(JSON.stringify({ op: 'removeMember', group: G, member: M, pad: 'x'.repeat(room) })).toString('base64');
    return { ...removal, payload: { ...removal.payload, blob } };
  };
  assert.equal(padBlob(65536).payload.blob.length, 65536);
  assert.equal(groups.checkRemovalRecords([padBlob(65536)], G, M), null, 'exactly 65536 is allowed');
  assert.equal(padBlob(65540).payload.blob.length, 65540);
  assert.equal(groups.checkRemovalRecords([padBlob(65540)], G, M), 'records', 'one quantum more is not');
  assert.equal(groups.checkRemovalRecords([{ ...removal, payload: { ...removal.payload, nonce: 'A'.repeat(64) } }], G, M), null);
  assert.equal(groups.checkRemovalRecords([{ ...removal, payload: { ...removal.payload, nonce: 'A'.repeat(68) } }], G, M), 'records');
  // The accept boundary: one removal and ten redistributions (eleven records).
  const ten = Array.from({ length: 10 }, (_, i) => rec('6', { group: G, from: M, currency: 'INR', shares: [] }, i + 2));
  assert.equal(groups.checkRemovalRecords([removal, ...ten], G, M), null);
  // Two records with one id cannot both be written.
  assert.equal(groups.checkRemovalRecords([removal, { ...moved, id: removal.id, hlc: removal.hlc }], G, M), 'records');
});

test('notices and their pushes say who did what, in the group\'s name', () => {
  const n = { groupId: G, groupName: 'Goa trip', byName: 'Prithvi', memberName: 'Sam' };
  assert.equal(groups.noticeBody('request', { ...n, byName: 'Asha', toPerson: false }), 'Asha asked to remove Sam');
  assert.equal(groups.noticeBody('request', { ...n, byName: 'Asha', toPerson: true }), 'Asha asked to remove you');
  assert.equal(groups.noticeBody('declined', { ...n, toPerson: false }), 'Prithvi kept Sam in Goa trip');
  assert.equal(groups.noticeBody('declined', { ...n, toPerson: true }), 'Prithvi kept you in Goa trip');
  assert.equal(groups.noticeBody('removed', n), 'Prithvi removed you from Goa trip');
  assert.equal(groups.noticeBody('role', { ...n, role: 'admin' }), 'Prithvi made you an admin of Goa trip');
  assert.equal(groups.noticeBody('role', { ...n, role: 'owner' }), 'Prithvi made you the owner of Goa trip');
  assert.equal(groups.noticeBody('deleted', n), 'Prithvi deleted Goa trip');
  const p = groups.noticePush({ kind: 'removed', ...n });
  assert.deepEqual(p.notification, { title: 'Goa trip', body: 'Prithvi removed you from Goa trip' });
  assert.deepEqual(p.data, { kind: 'group', groupId: G });
  assert.equal(p.android.notification.channelId, 'group-activity');
  assert.throws(() => groups.noticePush({ kind: 'removed', ...n, groupId: 'x' }));
  assert.equal(groups.requestNoticeId(G, M), `request-${G}-${M}`);
});

test('purge hands a group on: the first admin in join order, else the first member left', () => {
  const plan = account.groupsPlan([
    { id: 'a', memberUids: ['me', 'x', 'y'], createdBy: 'me', admins: ['y'] },
    { id: 'b', memberUids: ['me', 'x'], owner: 'me' },
    { id: 'c', memberUids: ['x', 'me'], createdBy: 'x' },
    { id: 'd', memberUids: ['me'], createdBy: 'me' },
  ], 'me');
  assert.deepEqual(plan, {
    leave: ['a', 'b', 'c'],
    destroy: ['d'],
    handOver: [{ id: 'a', owner: 'y' }, { id: 'b', owner: 'x' }],
  });
});

test('D-210 review: an owner no longer in the group (a creator purged before D-210) hands the seat on', () => {
  assert.equal(groups.ownerOf({ memberUids: ['m1', 'ad', 'm2'], createdBy: 'gone', admins: ['gone', 'm2', 'ad'] }), 'ad',
    'the first admin in member order');
  assert.equal(groups.ownerOf({ memberUids: ['m1', 'm2'], createdBy: 'gone' }), 'm1', 'no admin: the first member');
  assert.equal(groups.ownerOf({ memberUids: ['m1', 'm2'], owner: 'gone', createdBy: 'm2' }), 'm1',
    '`owner` is the record; `createdBy` counts only when it is absent');
  assert.equal(groups.ownerOf({ memberUids: [], createdBy: 'gone' }), null);
  assert.equal(groups.ownerOf(g({ owner: 'ad' })), 'ad', 'a member owner stands');
  const old = { memberUids: ['m1', 'm2'], createdBy: 'gone' };
  assert.equal(groups.can('remove', old, 'm1', 'm2'), true);
  assert.equal(groups.can('delete', old, 'm1', null), true);
  assert.equal(groups.can('remove', old, 'm2', 'm1'), false, 'nobody removes the owner it fell to');
});

test('D-210 review: a removal record\'s clock is never negative', () => {
  const at = (millis, counter) => ({ ...removal, id: `${millis}-${counter}-${D}`, hlc: { millis, counter, device: D } });
  assert.equal(groups.checkRemovalRecords([at(0, 0)], G, M), null);
  assert.equal(groups.checkRemovalRecords([at(-1, 0)], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([at(5, -1)], G, M), 'records');
});

test('D-210 fix 2: a place is removed only when its latest removal is newer than its latest add', () => {
  const rm = (createdAt, member = 'x', removal = true) => ({ createdAt, removal, blob: { op: 'removeMember', member } });
  const add = (createdAt, id = 'x') => ({ createdAt, removal: false, blob: { op: 'addMember', member: { id } } });
  assert.equal(groups.removedSince([], 'x'), false, 'never removed');
  assert.equal(groups.removedSince([add(1)], 'x'), false, 'added only');
  assert.equal(groups.removedSince([add(1), rm(2)], 'x'), true, 'removed after the add');
  assert.equal(groups.removedSince([rm(2)], 'x'), true, 'removed, never added in this log');
  assert.equal(groups.removedSince([add(1), rm(2), add(3)], 'x'), false, 'added back');
  assert.equal(groups.removedSince([add(1), rm(2), add(3), rm(4)], 'x'), true, 'removed again');
  assert.equal(groups.removedSince([rm(4), add(3), rm(2), add(1)], 'x'), true, 'order of the list does not matter');
  assert.equal(groups.removedSince([add(1), rm(2, 'x', false)], 'x'), false, 'a phone-written removeMember is not a removal');
  assert.equal(groups.removedSince([add(1), rm(2, 'y')], 'x'), false, 'another place');
  assert.equal(groups.removedSince([rm(2), add(3, 'y')], 'x'), true, 'another place added');
  assert.equal(groups.removedSince([rm(2), { createdAt: 3, removal: false, blob: { op: 'claim', member: 'x' } }], 'x'), true, 'a claim is not an add');
});

test('D-210 fix 3: a place\'s account is the one its accepted invite joined, among the members', () => {
  const inv = (usedBy) => ({ usedBy });
  const members = ['ow', 'sam', 'kai'];
  assert.deepEqual(groups.joinedAs([], members), { uid: null, contested: false }, 'never joined');
  assert.deepEqual(groups.joinedAs([inv(null)], members), { uid: null, contested: false }, 'an unused invite joined nobody');
  assert.deepEqual(groups.joinedAs([inv('sam')], members), { uid: 'sam', contested: false });
  assert.deepEqual(groups.joinedAs([inv('sam'), inv(null), inv('sam')], members), { uid: 'sam', contested: false },
    'the same account twice is one account');
  assert.deepEqual(groups.joinedAs([inv('gone')], members), { uid: null, contested: false }, 'an account no longer a member');
  assert.deepEqual(groups.joinedAs([inv('gone'), inv('kai')], members), { uid: 'kai', contested: false },
    'invited again to another account after the first left');
  assert.deepEqual(groups.joinedAs([inv('sam'), inv('kai')], members), { uid: null, contested: true }, 'two members: refused');
  assert.deepEqual(groups.joinedAs([inv(''), inv(5), {}, null], members), { uid: null, contested: false }, 'malformed is nobody');
  assert.deepEqual(groups.joinedAs([inv('sam')], undefined), { uid: null, contested: false });
});

test('D-210 fix 4: an invite that ended (its account was removed from the place) joins nobody', () => {
  const inv = (usedBy, endedAt) => ({ usedBy, endedAt });
  const members = ['ow', 'sam', 'kai'];
  assert.deepEqual(groups.joinedAs([inv('sam', 5)], members), { uid: null, contested: false },
    'sam left the place and is a member again elsewhere: the old invite does not count');
  assert.deepEqual(groups.joinedAs([inv('sam', { seconds: 1 })], members), { uid: null, contested: false }, 'a timestamp value');
  assert.deepEqual(groups.joinedAs([inv('sam', 5), inv('sam')], members), { uid: 'sam', contested: false },
    'joined the place again through a new invite');
  assert.deepEqual(groups.joinedAs([inv('sam', 5), inv('kai')], members), { uid: 'kai', contested: false },
    'an ended invite never contests');
  assert.deepEqual(groups.joinedAs([inv('sam', null), inv('kai', undefined)], members), { uid: null, contested: true },
    'no endedAt: both still count');
});

test('D-218: placeHeld: a member\'s own place, an accepted invite or a contested place is held; nothing else is', () => {
  const none = { uid: null, contested: false };
  assert.equal(groups.placeHeld([], none), false, 'nobody holds it');
  assert.equal(groups.placeHeld(['ow'], none), true, 'the creator, on its creator place (D-227; a member\'s own participant id before)');
  assert.equal(groups.placeHeld([], { uid: 'sam', contested: false }), true, 'an accepted invite of a member');
  assert.equal(groups.placeHeld([], { uid: null, contested: true }), true, 'contested is held too');
  assert.equal(groups.placeHeld(['a', 'b'], none), true, 'two accounts with the one id');
  assert.equal(groups.placeHeld(null, undefined), false, 'malformed is nobody');
  assert.equal(groups.placeHeld('ow', none), false, 'not a list counts as empty');
  assert.equal(groups.placeHeld([], null), false, 'no invite summary counts as none');
  // Composed with joinedAs, as refuseHeld reads it: an invite that ended, or
  // whose account left the group, holds nothing.
  const held = (invites, members) => groups.placeHeld([], groups.joinedAs(invites, members));
  assert.equal(held([{ usedBy: 'sam' }], ['ow', 'sam']), true, 'accepted by a current member');
  assert.equal(held([{ usedBy: 'sam', endedAt: 5 }], ['ow', 'sam']), false, 'ended');
  assert.equal(held([{ usedBy: 'sam' }], ['ow']), false, 'the account left');
  assert.equal(held([{ usedBy: null }], ['ow', 'sam']), false, 'an open invite holds nothing');
  assert.equal(held([{ usedBy: 'sam' }, { usedBy: 'kai' }], ['ow', 'sam', 'kai']), true, 'two accounts: contested');
});

test('D-210 final (A4): adopted records are any removals and redistributions of the group, well formed, bounded', () => {
  const other = 'e'.repeat(32);
  const sam = 'a'.repeat(32);
  assert.equal(groups.checkAdoptedRecords([removal], G), null);
  assert.equal(groups.checkAdoptedRecords([moved], G), null, 'a redistribution alone');
  assert.equal(groups.checkAdoptedRecords([removal, moved, rec('3', { op: 'removeMember', group: G, member: sam }, 3)], G), null,
    'several people');
  assert.equal(groups.checkAdoptedRecords(Array.from({ length: 50 }, (_, i) => rec('3', { op: 'removeMember', group: G, member: M }, 10 + i)), G), null);
  for (const [bad, why] of [
    [[], 'none'], ['x', 'not a list'], [null, 'null'],
    [Array.from({ length: 51 }, (_, i) => rec('3', { op: 'removeMember', group: G, member: M }, 10 + i)), 'over fifty'],
    [[rec('3', { op: 'addMember', group: G, member: { id: M, name: 'x' } })], 'another op'],
    [[rec('3', { op: 'removeMember', group: other, member: M })], 'another group'],
    [[rec('3', { op: 'removeMember', group: G, member: 'm' })], 'a member that is not an id'],
    [[rec('6', { group: other, from: M, currency: 'INR', shares: [] })], 'another group\'s redistribution'],
    [[rec('6', { group: G, from: 'm', currency: 'INR', shares: [] })], 'a from that is not an id'],
    [[rec('1', {})], 'an expense'],
    [[removal, removal], 'one id twice'],
    [[{ ...removal, id: 'x' }], 'an id that is not its clock'],
    [[{ ...removal, payload: { ...removal.payload, blob: 'not base64!' } }], 'bad base64'],
    [[{ ...removal, payload: { ...removal.payload, blob: Buffer.from('{not json').toString('base64') } }], 'bad JSON'],
  ]) {
    assert.equal(groups.checkAdoptedRecords(bad, G), 'records', why);
  }
});

// ---- D-211: shareProfile's pure check ----

const profileRec = (group, member, name, extra = {}, n = 1) =>
  rec('3', { op: 'memberProfile', group, member, name, ...extra }, n);
/** The hex of a JPEG frame header (SOF, marker `marker`) declaring `width` x `height`. */
const sof = (width, height, marker = 'c0') =>
  `ff${marker}001108${height.toString(16).padStart(4, '0')}${width.toString(16).padStart(4, '0')}03011100021101031101`;
// Lowercase hex of `bytes` bytes: the JPEG start, a 120 x 120 frame header (D-235), then filler.
const JPEG = (bytes) => `ffd8${sof(120, 120)}${'ab'.repeat(bytes - 21)}`;
const gid = (i) => i.toString(16).padStart(32, '0');

test('a profile record is one well formed memberProfile per group, named as the account is named', () => {
  assert.equal(groups.checkProfileRecords([profileRec(G, M, 'Mo')], 'Mo'), null);
  assert.equal(groups.checkProfileRecords([profileRec(G, M, 'Mo', { emoji: '🦊' })], 'Mo'), null);
  assert.equal(groups.checkProfileRecords([profileRec(G, M, 'Mo', { thumb: JPEG(12000) })], 'Mo'), null, '12,000 bytes is the limit');
  const fifty = Array.from({ length: 50 }, (_, i) => profileRec(gid(i + 1), M, 'Mo', {}, i + 1));
  assert.equal(groups.checkProfileRecords(fifty, 'Mo'), null, 'fifty groups');
  assert.equal(groups.checkProfileRecords([...fifty, profileRec(gid(51), M, 'Mo', {}, 51)], 'Mo'), 'records', 'fifty-one');
  assert.equal(groups.checkProfileRecords([], 'Mo'), 'records');
  assert.equal(groups.checkProfileRecords('x', 'Mo'), 'records');
  assert.equal(groups.checkProfileRecords([profileRec(G, M, 'Mo', {}, 1), profileRec(G, M, 'Mo', {}, 2)], 'Mo'), 'records', 'two for one group');
  assert.equal(groups.checkProfileRecords([profileRec(G, M, 'Mo', {}, 1), profileRec(gid(2), M, 'Mo', {}, 1)], 'Mo'), 'records', 'an id twice');
  assert.equal(groups.checkProfileRecords([rec('6', { op: 'memberProfile', group: G, member: M, name: 'Mo' })], 'Mo'), 'records', 'type 6');
  assert.equal(groups.checkProfileRecords([rec('3', { op: 'renameMember', group: G, member: M, name: 'Mo' })], 'Mo'), 'records', 'another op');
  assert.equal(groups.checkProfileRecords([profileRec(G, 'nope', 'Mo')], 'Mo'), 'records', 'a bad member id');
  assert.equal(groups.checkProfileRecords([profileRec(G, M, 'Mo', { emoji: '🦊', thumb: JPEG(100) })], 'Mo'), 'records', 'emoji with a thumb');
  assert.equal(groups.checkProfileRecords([profileRec(G, M, 'Mo', { thumb: JPEG(12001) })], 'Mo'), 'records', '12,001 bytes');
  assert.equal(groups.checkProfileRecords([profileRec(G, M, 'Mo', { thumb: '89504e47' + 'ab'.repeat(100) })], 'Mo'), 'records', 'not a JPEG');
  assert.equal(groups.checkProfileRecords([profileRec(G, M, 'Mo', { thumb: JPEG(100).toUpperCase() })], 'Mo'), 'records', 'upper-case hex');
  assert.equal(groups.checkProfileRecords([profileRec(G, M, 'Mo', { bio: 'x' })], 'Mo'), 'records', 'an unknown field');
  assert.equal(groups.checkProfileRecords([profileRec(G, M, ' Mo')], ' Mo'), 'records', 'outer spaces');
  assert.equal(groups.checkProfileRecords([profileRec(G, M, '')], ''), 'records', 'no name');
  assert.equal(groups.checkProfileRecords([profileRec(G, M, 'Mona')], 'Mo'), 'name', 'well formed, but not the account\'s name');
  assert.equal(groups.checkProfileRecords([profileRec(G, M, 'Mo')], null), 'name', 'an account with no name');
});

test('M2: a profile\'s name and emoji are checked as core checks them (check_member_name, check_emoji)', () => {
  const one = (name, extra = {}) => groups.checkProfileRecords([profileRec(G, M, name, extra)], name);
  // Names: trimmed, 1 to 64 characters (code points), no control characters.
  assert.equal(one('x'.repeat(64)), null, '64 characters');
  assert.equal(one('\u{1F33F}'.repeat(64)), null, '64 characters of four bytes each: characters, not units, count');
  assert.equal(one('x'.repeat(65)), 'records', '65 characters');
  assert.equal(one('M\u0007o'), 'records', 'a control character inside');
  assert.equal(one('Mo\u0085'), 'records', 'NEL: a control character (and white space) at the end');
  assert.equal(one(' Mo'), 'records', 'a no-break space is white space to core');
  assert.equal(one('　Mo'), 'records', 'an ideographic space too');
  assert.equal(one('Mo﻿'), null, 'U+FEFF is neither white space nor a control character to core (JS trim would drop it)');
  assert.equal(one('M\ud800o'), 'records', 'a lone surrogate never decodes in core');
  // Emoji: trimmed, 1 to 8 characters, at most 32 bytes, no control characters.
  assert.equal(one('Mo', { emoji: '\u{1F98A}'.repeat(8) }), null, 'eight characters, 32 bytes');
  assert.equal(one('Mo', { emoji: '\u{1F98A}'.repeat(9) }), 'records', 'nine characters');
  assert.equal(one('Mo', { emoji: ' \u{1F98A}' }), 'records', 'an outer space');
  assert.equal(one('Mo', { emoji: '\u{1F98A}\u0007' }), 'records', 'a control character');
  assert.equal(one('Mo', { emoji: '\u{1F98A} ' }), 'records', 'a line separator is white space to core');
  assert.equal(one('Mo', { emoji: '\ud83e' }), 'records', 'a lone surrogate');
  assert.equal(one('Mo', { emoji: '' }), 'records', 'empty');
  assert.equal(one('Mo', { emoji: 7 }), 'records', 'not a string');
});

test('D-227: creatorPlaceOf: the first member of the creator\'s earliest create, by server time, then the HLC', () => {
  const A = 'a'.repeat(32);
  const B = 'b'.repeat(32);
  const C = 'c'.repeat(32);
  const create = (authorUid, createdAt, hlc, members) => ({
    authorUid, createdAt, hlc: { counter: 0, device: 'f'.repeat(32), ...hlc }, blob: { op: 'create', group: 'g', members },
  });
  const one = (id) => [{ id, name: 'N' }];
  assert.equal(groups.creatorPlaceOf([create('ow', 10, { millis: 1 }, [{ id: A }, { id: B }, { id: C }])], 'ow'), A, 'members[0], not another');
  // Only the creator's own creates count, however early another member's is.
  assert.equal(groups.creatorPlaceOf([create('me', 5, { millis: 1 }, one(B)), create('ow', 10, { millis: 2 }, one(A))], 'ow'), A);
  assert.equal(groups.creatorPlaceOf([create('me', 5, { millis: 1 }, one(B))], 'ow'), null);
  // The earliest by the server's createdAt, whatever the HLC (a phone chooses its own clock).
  assert.equal(groups.creatorPlaceOf([create('ow', 20, { millis: 1 }, one(B)), create('ow', 10, { millis: 9 }, one(A))], 'ow'), A);
  assert.equal(groups.creatorPlaceOf([create('ow', 10, { millis: 9 }, one(A)), create('ow', 20, { millis: 1 }, one(B))], 'ow'), A, 'either order');
  // One server time: the HLC's millis, then counter, then device.
  assert.equal(groups.creatorPlaceOf([create('ow', 10, { millis: 2 }, one(B)), create('ow', 10, { millis: 1 }, one(A))], 'ow'), A);
  assert.equal(groups.creatorPlaceOf([create('ow', 10, { millis: 1, counter: 1 }, one(B)), create('ow', 10, { millis: 1 }, one(A))], 'ow'), A);
  assert.equal(groups.creatorPlaceOf([create('ow', 10, { millis: 1, device: 'e'.repeat(32) }, one(A)), create('ow', 10, { millis: 1 }, one(B))], 'ow'), A);
  // The earliest create's first member malformed: null, never the next create's.
  for (const bad of [[{ id: 'A'.repeat(32) }], [{ id: 'x' }], [{}], [], 'x', null]) {
    assert.equal(groups.creatorPlaceOf([create('ow', 10, { millis: 1 }, bad), create('ow', 20, { millis: 1 }, one(A))], 'ow'), null, JSON.stringify(bad));
  }
  // No create, other ops only, no creator recorded.
  assert.equal(groups.creatorPlaceOf([], 'ow'), null);
  assert.equal(groups.creatorPlaceOf(null, 'ow'), null);
  assert.equal(groups.creatorPlaceOf([{ ...create('ow', 10, { millis: 1 }, one(A)), blob: { op: 'addMember', group: 'g', member: { id: A } } }], 'ow'), null);
  assert.equal(groups.creatorPlaceOf([create('ow', 10, { millis: 1 }, one(A))], undefined), null);
  assert.equal(groups.creatorPlaceOf([create(undefined, 10, { millis: 1 }, one(A))], undefined), null);
});

// ---- D-229: shareAliases' pure check ----

const aliasRec = (group, member, you, n = 1, extra = {}) => rec('3', { op: 'placeAlias', group, member, you, ...extra }, n);

test('D-229: an alias record is one well formed placeAlias, three ids, `you` not its place, one per (group, you)', () => {
  const X = 'a'.repeat(32);
  const Y = 'b'.repeat(32);
  const G2 = 'f'.repeat(32);
  // The golden bytes a phone builds (core's GroupOp::PlaceAlias, serde field order).
  const golden = { ...aliasRec(G, X, Y), payload: { ...aliasRec(G, X, Y).payload,
    blob: Buffer.from(`{"op":"placeAlias","group":"${G}","member":"${X}","you":"${Y}"}`).toString('base64') } };
  assert.equal(groups.checkAliasRecords([golden]), null);
  assert.equal(groups.checkAliasRecords([aliasRec(G, X, Y, 1), aliasRec(G2, X, Y, 2)]), null, 'one id in two groups');
  assert.equal(groups.checkAliasRecords([aliasRec(G, X, Y, 1), aliasRec(G, X, M, 2)]), null, 'two ids onto one place');
  const fifty = Array.from({ length: 50 }, (_, i) => aliasRec(G, X, gid(i + 1), i + 1));
  assert.equal(groups.checkAliasRecords(fifty), null, 'fifty');
  for (const [bad, why] of [
    [[], 'none'], ['x', 'not a list'], [null, 'null'], [undefined, 'missing'],
    [[...fifty, aliasRec(G, X, gid(51), 51)], 'fifty-one'],
    [[aliasRec(G, X, X)], '`you` is the place itself'],
    [[aliasRec(G, X, Y, 1), aliasRec(G, M, Y, 2)], 'one (group, you) twice, onto two places'],
    [[aliasRec(G, X, Y, 1), aliasRec(G, X, Y, 2)], 'one (group, you) twice, onto one place'],
    [[aliasRec(G, X, Y, 1), aliasRec(G2, X, M, 1)], 'one record id twice'],
    [[rec('6', { op: 'placeAlias', group: G, member: X, you: Y })], 'type 6'],
    [[rec('1', { op: 'placeAlias', group: G, member: X, you: Y })], 'type 1'],
    [[rec('3', { op: 'claim', group: G, member: X, you: Y, devices: [] })], 'a claim'],
    [[rec('3', { op: 'memberProfile', group: G, member: X, name: 'Al' })], 'a profile'],
    [[aliasRec('g', X, Y)], 'a group that is not an id'],
    [[aliasRec(G, 'x', Y)], 'a member that is not an id'],
    [[aliasRec(G, X, 'y')], 'a you that is not an id'],
    [[aliasRec(G, X, Y.toUpperCase())], 'upper-case hex'],
    [[aliasRec(G, X, 7)], 'a number'],
    [[aliasRec(G, X, Y, 1, { devices: [] })], 'an unknown field'],
    [[rec('3', { op: 'placeAlias', group: G, member: X })], 'no you'],
    [[{ ...aliasRec(G, X, Y), id: 'x' }], 'an id that is not its clock'],
    [[{ ...aliasRec(G, X, Y), payload: { ...aliasRec(G, X, Y).payload, blob: 'not base64!' } }], 'bad base64'],
    [[{ ...aliasRec(G, X, Y), payload: { ...aliasRec(G, X, Y).payload, blob: Buffer.from('{not json').toString('base64') } }], 'bad JSON'],
    [[{ ...aliasRec(G, X, Y), payload: { ...aliasRec(G, X, Y).payload, blob: Buffer.from('null').toString('base64') } }], 'JSON null'],
  ]) {
    assert.equal(groups.checkAliasRecords(bad), 'records', why);
  }
});

// ---- D-230: sharePlaces' pure check ----

const heldRec = (group, member, you, n = 1, extra = {}) => rec('3', { op: 'heldPlace', group, member, you, ...extra }, n);

test('D-230: a held record is one well formed heldPlace, three ids, `you` may be the place, one per (group, member, you)', () => {
  const X = 'a'.repeat(32);
  const Y = 'b'.repeat(32);
  const G2 = 'f'.repeat(32);
  // The golden bytes a phone builds (core's GroupOp::HeldPlace, serde field order).
  const golden = { ...heldRec(G, X, Y), payload: { ...heldRec(G, X, Y).payload,
    blob: Buffer.from(`{"op":"heldPlace","group":"${G}","member":"${X}","you":"${Y}"}`).toString('base64') } };
  assert.equal(groups.checkHeldRecords([golden]), null);
  assert.equal(groups.checkHeldRecords([heldRec(G, X, X)]), null, 'the creator\'s raw place');
  assert.equal(groups.checkHeldRecords([heldRec(G, X, Y, 1), heldRec(G2, X, Y, 2)]), null, 'one place in two groups');
  const fifty = Array.from({ length: 50 }, (_, i) => heldRec(G, gid(i + 1), Y, i + 1));
  assert.equal(groups.checkHeldRecords(fifty), null, 'fifty');
  // A5: a two-phone account marks one member once per id: two `you` for one member is valid.
  assert.equal(groups.checkHeldRecords([heldRec(G, X, Y, 1), heldRec(G, X, X, 2)]), null, 'one member, two ids');
  // A1: the join's bound is 11 (the new place and at most ten old ones).
  const eleven = fifty.slice(0, 11);
  assert.equal(groups.checkHeldRecords(eleven, 11), null, 'eleven at the join');
  assert.equal(groups.checkHeldRecords(fifty.slice(0, 12), 11), 'records', 'twelve at the join');
  for (const [bad, why] of [
    [[], 'none'], ['x', 'not a list'], [null, 'null'], [undefined, 'missing'],
    [[...fifty, heldRec(G, gid(51), Y, 51)], 'fifty-one'],
    [[heldRec(G, X, Y, 1), heldRec(G, X, Y, 2)], 'one (group, member, you) twice'],
    [[heldRec(G, X, Y, 1), heldRec(G2, M, Y, 1)], 'one record id twice'],
    [[rec('6', { op: 'heldPlace', group: G, member: X, you: Y })], 'type 6'],
    [[rec('3', { op: 'placeAlias', group: G, member: X, you: Y })], 'an alias'],
    [[rec('3', { op: 'claim', group: G, member: X, you: Y, devices: [] })], 'a claim'],
    [[heldRec(G, X, Y, 1, { extra: 1 })], 'an extra field'],
    [[heldRec('g', X, Y)], 'a group that is not an id'],
    [[heldRec(G, X, 'y')], 'a you that is not an id'],
  ]) assert.equal(groups.checkHeldRecords(bad), 'records', why);
});

// ---- D-235: the picture's declared size (Android audit H2), the group's name (Firebase audit M2) ----

test('D-235 H2: a JPEG\'s declared frame sizes, read as core reads them (jpeg_frame_sizes)', () => {
  const buf = (hex) => Buffer.from(hex, 'hex');
  const sizes = (hex) => groups.jpegFrameSizes(buf(hex));
  assert.deepEqual(sizes(`ffd8${sof(120, 90)}`), [{ width: 120, height: 90 }]);
  // APP0 (JFIF, 16 bytes) and a DQT before the frame, as real files have them; FF fill bytes before a marker.
  const app0 = 'ffe000104a46494600010100000100010000';
  const dqt = `ffdb0043${'01'.repeat(65)}`;
  assert.deepEqual(sizes(`ffd8${app0}${dqt}ffff${sof(512, 512, 'c2')}`), [{ width: 512, height: 512 }], 'progressive, after fill');
  assert.deepEqual(sizes(`ffd8${sof(10, 10)}${sof(7000, 7000, 'c1')}ffda000c`), [{ width: 10, height: 10 }, { width: 7000, height: 7000 }],
    'every frame before the scan');
  // The scan's header (SOS, 12 bytes with its length) is whole, so a reader that went on would find the next frame.
  assert.deepEqual(sizes(`ffd8${sof(10, 10)}ffda000c${'00'.repeat(10)}${sof(7000, 7000)}`), [{ width: 10, height: 10 }],
    'nothing after the first scan');
  assert.deepEqual(sizes(`ffd8ab12${sof(30, 40)}`), [{ width: 30, height: 40 }], 'bytes between markers are skipped');
  assert.deepEqual(sizes(`ffd8ffde000b080064006403`), [{ width: 100, height: 100 }], 'DHP (DE) declares a size too');
  assert.deepEqual(sizes(`ffd8ffc4000400ff${sof(10, 10)}`), [{ width: 10, height: 10 }], 'DHT (C4) is no frame');
  assert.deepEqual(sizes(`ffd8${app0}`), [], 'no frame at all');
  assert.deepEqual(sizes('ffd8ffc00011080078'), [], 'a frame header cut short');
  assert.deepEqual(groups.jpegFrameSizes('ffd8'), [], 'not a buffer');
});

test('D-235 H2: a member preview that declares a size no phone should draw is refused, as core refuses it', () => {
  const thumb = (w, h, more = '') => `ffd8${sof(w, h)}${more}${'ab'.repeat(50)}`;
  const one = (t) => groups.checkProfileRecords([profileRec(G, M, 'Mo', { thumb: t })], 'Mo');
  assert.equal(one(thumb(120, 120)), null);
  assert.equal(one(thumb(512, 512)), null, '512 a side and 512 x 512 pixels is the limit');
  assert.equal(one(thumb(513, 1)), 'records', 'too wide');
  assert.equal(one(thumb(1, 513)), 'records', 'too tall');
  assert.equal(one(thumb(7000, 7000)), 'records', 'the audit\'s 7000 x 7000');
  assert.equal(one(thumb(0, 120)), 'records', 'no width');
  assert.equal(one(thumb(120, 0)), 'records', 'a height left for later (DNL)');
  assert.equal(one(thumb(120, 120, sof(4000, 4000, 'c1'))), 'records', 'a second, larger frame');
  assert.equal(one(`ffd8ffe000104a46494600010100000100010000${'ab'.repeat(50)}`), null, 'no frame: as core, nothing to size');
  assert.equal(groups.frameSizesOk([{ width: 512, height: 512 }]), true);
  assert.equal(groups.frameSizesOk([{ width: 512, height: 513 }]), false);
  assert.equal(groups.THUMB_MAX_PIXELS, 262144);
});

test('D-235 M2: the group\'s name is its latest create or rename by HLC, else the document\'s, else "Group"', () => {
  const e = (millis, counter, op, name, device = 'a'.repeat(32)) => ({ hlc: { millis, counter, device }, blob: { op, group: G, name } });
  const create = e(1000, 0, 'create', 'Goa trip');
  assert.equal(groups.groupNameOf([create], 'Doc'), 'Goa trip');
  assert.equal(groups.groupNameOf([create, e(1001, 0, 'renameGroup', 'Goa 2026')], 'Doc'), 'Goa 2026');
  assert.equal(groups.groupNameOf([e(1002, 0, 'renameGroup', 'Later'), create, e(1001, 5, 'renameGroup', 'Earlier')], 'Doc'), 'Later',
    'by HLC, not by list order');
  assert.equal(groups.groupNameOf([e(1001, 0, 'renameGroup', 'B', 'b'.repeat(32)), e(1001, 0, 'renameGroup', 'A', 'a'.repeat(32))], 'Doc'), 'B',
    'the device breaks a tie');
  assert.equal(groups.groupNameOf([create, e(1001, 0, 'renameMember', 'Not the group')], 'Doc'), 'Goa trip', 'another op');
  assert.equal(groups.groupNameOf([create, e(1001, 0, 'renameGroup', '   ')], 'Doc'), 'Goa trip', 'an empty name is skipped');
  assert.equal(groups.groupNameOf([create, e(1001, 0, 'renameGroup', ' Goa 2026')], 'Doc'), 'Goa trip',
    'round 2 m2: a name with outer space is skipped, as core skips it (check_group_name), never trimmed');
  assert.equal(groups.groupNameOf([create, e(1001, 0, 'renameGroup', 'Goa\u00a0')], 'Doc'), 'Goa trip', 'Unicode white space too');
  assert.equal(groups.groupNameOf([create, e(1001, 0, 'renameGroup', 42)], 'Doc'), 'Goa trip', 'a name that is not text');
  assert.equal(groups.groupNameOf([create, e(1001, 0, 'renameGroup', 'x'.repeat(90))], 'Doc'), 'x'.repeat(80), '80 characters shown');
  assert.equal(groups.groupNameOf([create, e(1001, 0, 'renameGroup', `${'x'.repeat(79)} yz`)], 'Doc'), 'x'.repeat(79),
    'a cut that ends in a space drops it');
  assert.equal(groups.groupNameOf([], ' Doc '), 'Doc', 'no op: the document\'s name');
  assert.equal(groups.groupNameOf(null, null), 'Group');
});

// ---- D-235 round 2 ----

test('D-235 round 2 C1: a compact op keeps only what the Functions read, members in their places (round 3: at most 500)', () => {
  const A = 'a'.repeat(32);
  const B = 'b'.repeat(32);
  assert.deepEqual(groups.compactOp({ op: 'create', group: G, name: 'Goa trip', settlementCurrency: 'INR',
    members: [{ id: A, name: 'Al' }, { id: 'nope', name: 'X' }, { id: B, name: 'Bo' }] }),
  { op: 'create', group: G, members: [{ id: A }, { id: null }, { id: B }], name: 'Goa trip' });
  assert.deepEqual(groups.compactOp({ op: 'addMember', group: G, member: { id: A, name: 'Al' } }), { op: 'addMember', group: G, member: { id: A } });
  assert.deepEqual(groups.compactOp({ op: 'claim', group: G, member: A, you: B, devices: [{ id: 'x' }] }),
    { op: 'claim', group: G, member: A, you: B });
  assert.deepEqual(groups.compactOp({ op: 'groupPicture', group: G, photo: 'ffd8ff' + 'ab'.repeat(1000) }), { op: 'groupPicture', group: G });
  assert.deepEqual(groups.compactOp({ op: 'renameGroup', group: G, name: ' padded' }), { op: 'renameGroup', group: G }, 'a name core refuses');
  assert.equal(groups.compactOp({ op: 'renameGroup', group: G, name: 'y'.repeat(500) }).name, 'y'.repeat(80));
  assert.equal(groups.opBytes(groups.compactOp({ op: 'create', group: G, members: Array.from({ length: 10 }, () => ({ id: A })) })), 1500);
  assert.equal(groups.opBytes(groups.compactOp({ op: 'renameGroup', group: G, name: 'Goa' })), 306);
  // Round 3 (R2-2): at most MEMBERS_MAX members an op are read, in their places; a zero-filled op costs no more.
  const big = groups.compactOp({ op: 'create', group: G, members: Array.from({ length: 98000 }, (_, i) => (i === 600 ? { id: A } : 0)) });
  assert.equal(big.members.length, groups.MEMBERS_MAX);
  assert.equal(groups.MEMBERS_MAX, 500);
  assert.ok(!big.members.some((m) => m.id === A), 'a member past the 500th is not read');
  assert.equal(groups.opBytes(big), 300 + 120 * 500);
});

test('D-235 round 2 m1: a record the Functions write keeps decision 105: its stamp at most 24 hours past the server\'s clock', () => {
  const at = (millis) => {
    const r = rec('3', { op: 'removeMember', group: G, member: M }, 1);
    return { ...r, hlc: { ...r.hlc, millis }, id: `${millis}-0-${r.hlc.device}` };
  };
  const hour = 3600000;
  assert.equal(groups.checkRemovalRecords([at(Date.now() + 23 * hour)], G, M), null, '23 hours ahead');
  assert.equal(groups.checkRemovalRecords([at(Date.now() + 25 * hour)], G, M), 'records', '25 hours ahead');
  assert.equal(groups.checkRemovalRecords([at(2 ** 53 - 1)], G, M), 'records', 'the largest safe integer');
});
