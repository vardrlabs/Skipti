// D-196: what onGroupRecord sends (firebase/functions/push.js), tested
// directly: who is told, which phones, the message, and which push addresses
// are dropped. No emulator needed; run with the other tests by the gate.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as push from '../functions/push.js';

const D = 'b'.repeat(32);
const G = 'a'.repeat(32);
const E = `1790000000123:7:${D}`;

test('a notice is a short text and an expense id; anything else is no notice', () => {
  assert.deepEqual(push.notice({ notice: { text: ' Bob added Dinner · ₹1,200.00 ', expense: E } }),
    { text: 'Bob added Dinner · ₹1,200.00', expense: E });
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

test('the message: the group as the title, the notice as the body, the expense to open', () => {
  const m = push.message({ text: 'Bob added Dinner · ₹1,200.00', expense: E }, G, ' Goa trip ');
  assert.deepEqual(m.notification, { title: 'Goa trip', body: 'Bob added Dinner · ₹1,200.00' });
  assert.deepEqual(m.data, { kind: 'record', groupId: G, expense: E });
  assert.equal(m.apns.payload.aps['thread-id'], G);
  assert.equal(m.android.notification.channelId, 'group-activity');
  assert.equal(m.android.notification.tag, E, 'a later change to the same expense replaces its notification');
  assert.equal(push.message({ text: 't', expense: E }, G, '').notification.title, 'SKIPTI');
  assert.throws(() => push.message({ text: 't', expense: E }, 'g1', 'x'));
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
