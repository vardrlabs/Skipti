// D-235: how the Functions deploy. Checked against the pinned firebase-tools'
// own code (firebase/node_modules/firebase-tools), so what is asserted here is
// what a deploy does, not what this file assumes. Nothing is deployed.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const fsAsync = require('../node_modules/firebase-tools/lib/fsAsync.js');
const config = JSON.parse(readFileSync(new URL('../firebase.json', import.meta.url), 'utf8')).functions;
const source = fileURLToPath(new URL('../functions', import.meta.url));
const packer = readFileSync(new URL('../node_modules/firebase-tools/lib/deploy/functions/prepareFunctionsUpload.js', import.meta.url), 'utf8');

test('D-235 L5: the Functions upload leaves node_modules and local files out, keeping firebase-tools\' defaults', async () => {
  // packageSource: `config.ignore || ["node_modules", ".git"]`, then the debug logs: a list REPLACES the defaults.
  assert.match(packer, /const ignore = config\.ignore \|\| \["node_modules", "\.git"\];/, 'the pinned firebase-tools still works this way');
  for (const kept of ['node_modules', '.git', 'firebase-debug.log', 'firebase-debug.*.log', '*.local']) {
    assert.ok(config.ignore.includes(kept), `${kept} is ignored`);
  }
  const ignore = [...config.ignore, 'firebase-debug.log', 'firebase-debug.*.log', '.runtimeconfig.json'];
  const files = (await fsAsync.readdirRecursive({ path: source, ignoreStrings: ignore })).map((f) => relative(source, f.name));
  assert.deepEqual(files.filter((f) => f.split('/').includes('node_modules')), [], 'no installed package is uploaded');
  for (const needed of ['index.js', 'groups.js', 'package.json', 'package-lock.json']) {
    assert.ok(files.includes(needed), `${needed} is uploaded`);
  }
  assert.ok(files.some((f) => f.startsWith('blocklist/')), 'the username blocklist is uploaded');
});

test('D-235 M1: every Function runs with its memory set (512 MiB), never the 256 MiB default', async () => {
  process.env.GCLOUD_PROJECT ??= 'demo-skipti';
  const fns = await import('../functions/index.js');
  const endpoints = Object.entries(fns).filter(([, f]) => f?.__endpoint);
  assert.ok(endpoints.length >= 20, `${endpoints.length} Functions`);
  for (const [name, f] of endpoints) {
    assert.equal(f.__endpoint.availableMemoryMb, 512, name);
    // Round 2 (I3): at most six calls an instance (firebase-tools would give 80 at 512 MiB): index.js states the arithmetic.
    assert.equal(f.__endpoint.concurrency, 6, `${name}: concurrency`);
  }
  assert.equal(fns.purgeDeletedAccounts.__endpoint.timeoutSeconds, 1800, 'the purge has the time its pages need');
});

test('D-235 round 2: the composite indexes opsOf\'s queries need are declared (the emulator never asks for one)', () => {
  const src = readFileSync(new URL('../functions/index.js', import.meta.url), 'utf8');
  assert.match(src, /ref\.collection\('records'\)\.where\('type', '==', '3'\);/, 'opsOf filters type');
  assert.match(src, /base\.where\('authorUid', '==', author\)\.orderBy\('createdAt'\)/, 'then authorUid, ordered by createdAt');
  // Round 3 (decision 113): the used invites of a group, an equality and a range on two fields.
  assert.match(src, /db\.collection\('invites'\)\.where\('groupId', '==', ref\.id\)\.where\('usedBy', '>', ''\)/);
  const { indexes } = JSON.parse(readFileSync(new URL('../firestore.indexes.json', import.meta.url), 'utf8'));
  assert.deepEqual(indexes.filter((i) => i.collectionGroup === 'invites').map((i) => ({ scope: i.queryScope, fields: i.fields })), [{
    scope: 'COLLECTION', fields: [{ fieldPath: 'groupId', order: 'ASCENDING' }, { fieldPath: 'usedBy', order: 'ASCENDING' }],
  }]);
  assert.deepEqual(indexes.filter((i) => i.collectionGroup === 'records').map((i) => ({ scope: i.queryScope, fields: i.fields })), [{
    scope: 'COLLECTION',
    fields: [{ fieldPath: 'type', order: 'ASCENDING' }, { fieldPath: 'authorUid', order: 'ASCENDING' }, { fieldPath: 'createdAt', order: 'ASCENDING' }],
  }]);
});

test('D-235 round 2 m3: the record version the rules and the Functions accept is core\'s RECORD_HEADER_VERSION', () => {
  const core = readFileSync(new URL('../../core/src/straumr/mod.rs', import.meta.url), 'utf8');
  const m = core.match(/^pub const RECORD_HEADER_VERSION: u16 = (\d+);$/m);
  assert.ok(m, 'core declares RECORD_HEADER_VERSION');
  const rules = readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8');
  const groupsSrc = readFileSync(new URL('../functions/groups.js', import.meta.url), 'utf8');
  assert.deepEqual([...rules.matchAll(/p\.version is int && p\.version == (\d+)/g)].map((x) => x[1]), [m[1]], 'the rules');
  assert.deepEqual([...groupsSrc.matchAll(/r\.payload\.version === (\d+)/g)].map((x) => x[1]), [m[1]], 'recordShape');
});
