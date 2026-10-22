// D-196: what onGroupRecord sends (firebase/functions/push.js), tested
// directly: who is told, which phones, the message, and which push addresses
// are dropped. No emulator needed; run with the other tests by the gate.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as push from '../functions/push.js';

const D = 'b'.repeat(32);
const G = 'a'.repeat(32);
const E = `1790000000123:7:${D}`;

test('the old notice (a 0.5.0 phone\'s) is a short text and an expense id; anything else is no notice', () => {
  assert.deepEqual(push.notice({ notice: { text: ' Bob added Dinner · ₹1,200.00 ', expense: E } }),
    { expense: E, legacy: true }, 'D-257: the text is checked, never kept');
  assert.equal(push.notice({}), null);
  assert.equal(push.notice({ notice: 'Bob added Dinner' }), null);
  assert.equal(push.notice({ notice: { text: '   ', expense: E } }), null);
  assert.equal(push.notice({ notice: { text: 'x'.repeat(201), expense: E } }), null);
  assert.equal(push.notice({ notice: { text: 'ok', expense: 'groups/x' } }), null);
  assert.equal(push.notice({ notice: { text: 'ok', expense: `1:2:${'B'.repeat(32)}` } }), null);
  assert.equal(push.notice({ notice: { text: 'ok' } }), null);
});

test('the other members are told, never the author, and nobody when the author is not a member', () => {
  assert.deepEqual(push.recipients({ memberUids: ['al', 'bo', 'cy'] }, 'bo'), ['al', 'cy']);
  assert.deepEqual(push.recipients({ memberUids: ['al', 'al', 'bo'] }, 'bo'), ['al']);
  assert.deepEqual(push.recipients({ memberUids: ['al'] }, 'al'), []);
  assert.deepEqual(push.recipients({ memberUids: ['al', 'bo'] }, 'mallory'), []);
  assert.deepEqual(push.recipients({}, 'al'), []);
  assert.deepEqual(push.recipients({ memberUids: ['al', 'bo'] }, undefined), []);
});

test('each phone with a push address is told once; signed-out phones are not', () => {
  const phones = [
    { uid: 'al', id: '1', data: { fcmToken: 't1' } },
    { uid: 'al', id: '2', data: { fcmToken: 't2', signedOut: true } },
    { uid: 'al', id: '3', data: {} },
    { uid: 'cy', id: '4', data: { fcmToken: 't1' } },
    { uid: 'cy', id: '5', data: { fcmToken: '' } },
    { uid: 'cy', id: '6', data: { fcmToken: 42 } },
    { uid: 'cy', id: '7', data: { fcmToken: 't7', signedOut: false } },
  ];
  assert.deepEqual(push.tokens(phones), [{ token: 't1', uid: 'al', id: '1' }, { token: 't7', uid: 'cy', id: '7' }]);
  const many = Array.from({ length: 600 }, (_, i) => ({ uid: 'al', id: String(i), data: { fcmToken: `t${i}` } }));
  assert.equal(push.tokens(many).length, push.MAX_TOKENS);
});

test('the message: the group as the title, the line as the body, the expense to open', () => {
  const m = push.message({ expense: E, kind: 'added', title: 'Dinner', amount: '₹1,200.00' }, G, ' Goa trip ', 'Bob');
  assert.deepEqual(m.notification, { title: 'Goa trip', body: 'Bob added Dinner · ₹1,200.00' });
  assert.deepEqual(m.data, { kind: 'record', groupId: G, expense: E });
  assert.equal(m.apns.payload.aps['thread-id'], G);
  assert.equal(m.android.notification.channelId, 'group-activity');
  assert.equal(m.android.notification.tag, E, 'a later change to the same expense replaces its notification');
  assert.equal(push.message({ expense: E, legacy: true }, G, '', 'Bob').notification.title, 'SKIPTI');
  assert.throws(() => push.message({ expense: E, legacy: true }, 'g1', 'x', 'Bob'));
  assert.equal(m.tokens, undefined, 'tokens are added by the caller');
});

test('only addresses FCM says are gone are dropped', () => {
  const sent = [{ token: 'a' }, { token: 'b' }, { token: 'c' }, { token: 'd' }];
  const response = { responses: [
    { success: true },
    { success: false, error: { code: 'messaging/registration-token-not-registered' } },
    { success: false, error: { code: 'messaging/internal-error' } },
    { success: false, error: { code: 'messaging/invalid-registration-token' } },
  ] };
  assert.deepEqual(push.deadTokens(sent, response), [{ token: 'b' }, { token: 'd' }]);
  assert.deepEqual(push.deadTokens(sent, {}), []);
});

// D-204: every send says how it went. Until then FCM refused every message
// (the runtime lacked cloudmessaging.messages.create) and nothing said so.
test('the outcome counts each send and names each failure, never a token', () => {
  const response = { responses: [
    { success: true },
    { success: false, error: { code: 'messaging/third-party-auth-error' } },
    { success: false, error: { code: 'messaging/third-party-auth-error' } },
    { success: false, error: { code: 'messaging/registration-token-not-registered' } },
    { success: false, error: { code: 'token AAAA:bbbb leaked here' } },
    { success: false },
  ] };
  assert.deepEqual(push.outcome(response), {
    delivered: 1, failed: 5,
    errors: { 'messaging/third-party-auth-error': 2, 'messaging/registration-token-not-registered': 1, other: 2 },
  });
  assert.deepEqual(push.outcome({ responses: [{ success: true }, { success: true }] }), { delivered: 2, failed: 0, errors: {} });
  assert.deepEqual(push.outcome(null), { delivered: 0, failed: 0, errors: {} });
  assert.ok(!JSON.stringify(push.outcome(response)).includes('AAAA'), 'a code that is not a code is "other"');
});

// D-257 (G-18): the line is built in the Function from checked fields and the author's server name.
test('D-257: the new notice: a kind, a one-line title, an amount (never on a delete); anything else is no notice', () => {
  const n = (over = {}) => ({ notice: { expense: E, kind: 'added', title: 'Dinner', amount: '₹1,200.00', ...over } });
  assert.deepEqual(push.notice(n()), { expense: E, kind: 'added', title: 'Dinner', amount: '₹1,200.00' });
  assert.deepEqual(push.notice(n({ title: ' Dinner ', amount: ' ₹5.00 ' })), { expense: E, kind: 'added', title: 'Dinner', amount: '₹5.00' });
  const { notice: { amount, ...deleted } } = n({ kind: 'deleted' });
  assert.deepEqual(push.notice({ notice: deleted }), { expense: E, kind: 'deleted', title: 'Dinner' });
  assert.equal(push.notice({ notice: { ...deleted, amount: '₹1.00' } }), null, 'an amount on a delete');
  assert.ok(push.notice(n({ title: 'x'.repeat(61) })));
  assert.ok(push.notice(n({ title: `${'😀'.repeat(30)}x` })), 'UTF-16 units, as the rules count: 61');
  assert.equal(push.notice(n({ title: '😀'.repeat(31) })), null, '31 emoji are 62 units');
  assert.equal(push.notice(n({ title: 'x'.repeat(62) })), null, 'a 62-character title');
  assert.ok(push.notice(n({ amount: 'x'.repeat(40) })));
  assert.equal(push.notice(n({ amount: 'x'.repeat(41) })), null, 'a 41-character amount');
  assert.equal(push.notice(n({ title: 'Dinner\nBob owes you' })), null, 'a line break in a title');
  assert.equal(push.notice(n({ title: 'Din ner' })), null, 'a line separator');
  assert.equal(push.notice(n({ title: 'Din\u0085ner' })), null, 'a C1 control');
  assert.equal(push.notice(n({ amount: '₹1\t.00' })), null, 'a control character in an amount');
  assert.equal(push.notice(n({ title: '   ' })), null, 'a blank title');
  assert.equal(push.notice(n({ title: '' })), null);
  assert.equal(push.notice(n({ kind: 'paid' })), null, 'an unknown kind');
  assert.equal(push.notice(n({ name: 'Alice' })), null, 'an extra key');
  assert.equal(push.notice(n({ text: 'Alice added Dinner' })), null, 'the old text beside the new fields');
  assert.equal(push.notice({ notice: { kind: 'added', title: 'Dinner' } }), null, 'no expense');
  assert.equal(push.notice(n({ title: 7 })), null);
});

test('D-257: the body names the author as the server knows them, never the phone\'s text', () => {
  const name = push.authorName({ name: '  Asha  ' });
  assert.equal(name, 'Asha');
  assert.equal(push.authorName(null), 'Someone');
  assert.equal(push.authorName({}), 'Someone');
  assert.equal(push.authorName({ name: '   ' }), 'Someone');
  assert.equal(push.authorName({ name: 42 }), 'Someone');
  assert.equal(push.authorName({ name: 'x'.repeat(60) }), 'x'.repeat(60));
  assert.equal(push.authorName({ name: 'x'.repeat(61) }), 'Someone', 'past the rules\' 60: not ours to cut');
  const m = (notice) => push.message(push.notice({ notice }), G, 'Goa trip', name).notification;
  assert.deepEqual(m({ expense: E, kind: 'added', title: 'Dinner', amount: '₹1,200.00' }),
    { title: 'Goa trip', body: 'Asha added Dinner · ₹1,200.00' });
  assert.equal(m({ expense: E, kind: 'changed', title: 'an expense', amount: '$5.00' }).body, 'Asha changed an expense · $5.00');
  assert.equal(m({ expense: E, kind: 'changed', title: 'Taxi' }).body, 'Asha changed Taxi', 'no amount, no separator');
  assert.equal(m({ expense: E, kind: 'deleted', title: 'an expense' }).body, 'Asha deleted an expense');
  // A 0.5.0 phone's old shape: its text is checked and never sent.
  const old = push.notice({ notice: { text: 'Rahul added Dinner · ₹1,200.00', expense: E } });
  assert.deepEqual(old, { expense: E, legacy: true });
  assert.equal(m({ text: 'Your account is locked: tap here', expense: E }).body, 'Asha changed an expense');
  assert.equal(push.message(old, G, 'Goa trip', 'Someone').notification.body, 'Someone changed an expense');
  assert.equal(push.notice({ notice: { text: 'ok', expense: E, kind: 'added' } }), null, 'old and new mixed');
  // The longest line: a 60-character name, a 61-character title and a 40-character amount.
  const long = push.message(push.notice({ notice: { expense: E, kind: 'changed', title: 't'.repeat(61), amount: 'a'.repeat(40) } }),
    G, 'g', 'n'.repeat(60)).notification.body;
  assert.equal(long, `${'n'.repeat(60)} changed ${'t'.repeat(61)} · ${'a'.repeat(40)}`);
});
