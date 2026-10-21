// D-197: finding people (firebase/functions/people.js), tested directly:
// usernames, email fingerprints, what counts as a search, results, the daily
// limits and the invite push. No emulator needed; run by the gate.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as people from '../functions/people.js';

// sha256("priya@example.com"), derived with Python's hashlib (not this code).
const PRIYA = '6bdb7e961d54ddc243ec721e6e211ac0a179b19a9ce2ad52a418198e146e69fe';

const ALLOWED = ['classic', 'peacock', 'grape', 'assam', 'method', 'prithvi_saha', 'kshitij', 'nazir', 'therapist',
  'badminton', 'pineapple', 'spicy', 'bobsled', 'tunnel', 'dallas', 'brandi', 'sunni_muslim', 'team_rocket',
  'me_and_you', 'supportive_sam', 'pakistani', 'hello', '@Priya_92'];
// scunthorpe is the known false refusal ("cunt" anywhere), kept on purpose.
const REFUSED = ['scunthorpe', 'f_u_c_k', 'fuuuck', 'b1tch', 'dick69', 'support', 'supp0rt', 'tech_support',
  'skipti_fan', 'x_spic_99', 'n4zi', 'sh1t', 'bullsh1t', 'booooobs', 'help', 'x1488x', 'asss', 'vardr_labs', 'SKIPTI'];
const BAD_SHAPE = ['me', 'ab', 'pri ya', 'a'.repeat(21)];

test('a username is 3 to 20 of a to z, 0 to 9 and _, stored lower case without @', () => {
  assert.deepEqual(people.username('  @Priya_92 '), { handle: 'priya_92' });
  assert.deepEqual(people.username('abc'), { handle: 'abc' });
  for (const bad of [...BAD_SHAPE, 'priya!', 'príya', 'pri.ya', '', '@']) {
    assert.deepEqual(people.username(bad), { error: 'shape' }, bad);
  }
  assert.deepEqual(people.username(42), { error: 'missing' });
});

// C2: the goldens come from .drafts/c2/golden.py, written from the spec's
// words, not from this code.
test('the blocklist refuses abuse and impersonation, in disguise too, and spares real names', () => {
  for (const ok of ALLOWED) assert.ok(people.username(ok).handle, ok);
  for (const no of REFUSED) assert.deepEqual(people.username(no), { error: 'unavailable' }, no);
});

test('a term matches with its letters repeated, never shrunk', () => {
  assert.equal(people.termPattern('boobs'), 'b+o{2,}b+s+');
  assert.equal(people.termPattern('aai_ghal'), 'a{2,}i+g+h+a+l+');
  assert.equal(people.termPattern('fuck'), 'f+u+c+k+');
});

test('set once, change once, never removed', () => {
  assert.deepEqual(people.usernameStep(null, null, false), { same: true });
  assert.deepEqual(people.usernameStep('al', 'al', true), { same: true });
  assert.deepEqual(people.usernameStep(null, 'al', false), { first: true });
  assert.deepEqual(people.usernameStep('al', 'bo', false), { change: true });
  assert.ok(people.usernameStep('al', 'bo', true).error, 'the one change is used');
  assert.ok(people.usernameStep('al', null, false).error, 'never removed');
});

test('the step\'s first set never spends the one change (two phones)', () => {
  assert.deepEqual(people.usernameStep(null, 'al', false, true), { first: true });
  assert.ok(people.usernameStep('al', 'bo', false, true).error, 'already set elsewhere: refused, not a change');
  assert.deepEqual(people.usernameStep('al', 'al', false, true), { same: true }, 'a retried first set is no change');
  assert.deepEqual(people.usernameStep('al', 'bo', false, false), { change: true }, 'the change screen sends no flag');
});

test('suggestions come from the name, well formed and never blocked, best first', () => {
  assert.deepEqual(people.suggestions('Prithvi Saha', 26),
    ['prithvi', 'prithvisaha', 'prithvi_saha', 'psaha', 'prithvi_s', 'sahaprithvi', 'prithvi26', 'prithvi_26']);
  assert.deepEqual(people.suggestions('  Príya ', 26), ['priya', 'priya26', 'priya_26', 'its_priya']);
  assert.deepEqual(people.suggestions('Al', 26), ['al26', 'al_26', 'its_al']);
  assert.deepEqual(people.suggestions('Support Team', 26), ['supportteam', 'steam', 'teamsupport']);
  assert.deepEqual(people.suggestions('राहुल', 26), []);
  assert.deepEqual(people.suggestions(42, 26), []);
});

test('an email fingerprint is SHA-256 of the email trimmed and lower case', () => {
  assert.equal(people.emailHash(' Priya@Example.COM '), PRIYA);
  assert.equal(people.emailHash('priya@example.com'), PRIYA);
  assert.notEqual(people.emailHash('priya+x@example.com'), PRIYA, 'no other normalisation');
});

test('a profile keeps a fingerprint only for a verified email, and only while findable', () => {
  const token = { email: 'Priya@example.com', email_verified: true };
  assert.equal(people.profileHash(true, token), PRIYA);
  assert.equal(people.profileHash(false, token), null);
  assert.equal(people.profileHash(true, { ...token, email_verified: false }), null);
  assert.equal(people.profileHash(true, { email_verified: true }), null);
  assert.equal(people.profileHash(true, { email: 'not-an-email', email_verified: true }), null);
  assert.equal(people.profileHash('yes', token), null);
});

test('a search is one username or up to 500 fingerprints, nothing else', () => {
  assert.deepEqual(people.search({ username: '@Priya' }), { username: 'priya' });
  assert.deepEqual(people.search({ emailHashes: [PRIYA, PRIYA] }), { emailHashes: [PRIYA] });
  assert.ok(people.search({ username: 'priya', emailHashes: [PRIYA] }).error, 'not both');
  assert.ok(people.search({}).error);
  assert.ok(people.search({ username: 'x' }).error);
  assert.ok(people.search({ emailHashes: [] }).error);
  assert.ok(people.search({ emailHashes: ['priya@example.com'] }).error, 'never a plain email');
  assert.ok(people.search({ emailHashes: [PRIYA.toUpperCase()] }).error);
  assert.ok(people.search({ emailHashes: Array(501).fill(PRIYA) }).error);
  assert.ok(!people.search({ emailHashes: Array(500).fill(PRIYA) }).error);
});

test('a result says who, never an email; findable unless turned off', () => {
  assert.deepEqual(people.person('u1', { name: ' Priya ', username: 'priya', emailHash: PRIYA, findable: true }, PRIYA),
    { uid: 'u1', name: 'Priya', username: 'priya', matched: PRIYA });
  assert.deepEqual(people.person('u2', {}), { uid: 'u2', name: null, username: null, matched: null });
  assert.equal(people.findable({}), true);
  assert.equal(people.findable({ findable: true }), true);
  assert.equal(people.findable({ findable: false }), false);
});

test('daily limits: 40 searches, 2000 fingerprints, 50 invites; a new UTC day starts again', () => {
  let s = null;
  for (let i = 0; i < 40; i++) s = people.charge(s, '2026-10-02', 'searches');
  assert.equal(s.searches, 40);
  assert.equal(people.charge(s, '2026-10-02', 'searches'), null);
  assert.equal(people.charge(s, '2026-10-03', 'searches').searches, 1);
  assert.equal(people.charge(null, '2026-10-02', 'hashes', 2000).hashes, 2000);
  assert.equal(people.charge(null, '2026-10-02', 'hashes', 2001), null);
  assert.equal(people.charge({ day: '2026-10-02', invites: 50 }, '2026-10-02', 'invites'), null);
  assert.equal(people.utcDay(Date.UTC(2026, 9, 2, 23, 59)), '2026-10-02');
  const c = people.charge(null, '2026-10-13', 'checks', 100);
  assert.equal(c.checks, 100);
  assert.equal(people.charge(c, '2026-10-13', 'checks'), null, 'the 101st check of the day is refused');
  // D-229 decision 48: shareAliases' alias records, 100 a day.
  const a = people.charge(null, '2026-10-18', 'aliases', 100);
  assert.equal(a.aliases, 100);
  assert.equal(people.charge(a, '2026-10-18', 'aliases'), null, 'the 101st alias record of the day is refused');
  assert.equal(people.charge(null, '2026-10-18', 'aliases', 101), null, 'one call past the limit');
});

test('an invite push says who and which group', () => {
  assert.deepEqual(people.invitePush('Prithvi', 'Goa trip').notification, { title: 'SKIPTI', body: 'Prithvi invited you to Goa trip' });
  assert.equal(people.invitePush(null, 'Goa trip').notification.body, 'Someone invited you to Goa trip');
  assert.deepEqual(people.invitePush('A', 'B').data, { kind: 'invite' });
});

// D-235 decision 121: an in-app invite's link, copy and push carry the
// server's names; the phone's inviterName and groupName are never read.
test('D-235 decision 121: an invitation names inviter and group from the server only, the push included', () => {
  const phone = { groupId: 'g', memberId: 'm', memberName: 'Bo', inviterName: 'SKIPTI Support', groupName: 'Your account is locked' };
  const n = people.invitation(phone, 'Al', 'Beach week');
  assert.deepEqual(n.push.notification, { title: 'SKIPTI', body: 'Al invited you to Beach week' });
  assert.deepEqual(n.copy, { groupId: 'g', memberId: 'm', memberName: 'Bo', groupName: 'Beach week', fromName: 'Al' });
  assert.deepEqual(n.link, { groupId: 'g', memberId: 'm', memberName: 'Bo', groupName: 'Beach week', inviterName: 'Al' });
  const nameless = people.invitation(phone, null, 'Goa trip');
  assert.equal(nameless.push.notification.body, 'Someone invited you to Goa trip', 'a nameless inviter is "Someone" in the push');
  assert.equal(nameless.copy.fromName, null, 'and nameless in the copy (the apps say "You are invited to")');
  assert.equal(nameless.link.inviterName, null);
  for (const v of [n, nameless]) assert.ok(!JSON.stringify(v).includes('SKIPTI Support') && !JSON.stringify(v).includes('locked'));
});
