// D-199 (the Firebase plan's unit 9): what Firebase Hosting serves on the
// invite domain. Pure: the files in firebase/public and the `hosting` section
// of firebase.json are read and checked against each other and against what
// the apps declare (the iOS entitlement and team, the Android package and
// intent filter), so neither side can drift alone. The live deploy is checked
// with curl (docs/CLOUD-SECURITY.md §8).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const config = JSON.parse(read('../firebase.json')).hosting;
const page = read('../public/join/index.html');
const missing = read('../public/404.html');
const privacy = read('../public/privacy/index.html');
const aasa = JSON.parse(read('../public/.well-known/apple-app-site-association'));
const links = JSON.parse(read('../public/.well-known/assetlinks.json'));

const headersFor = (source) => {
  const rule = config.headers.find((h) => h.source === source);
  assert.ok(rule, `a header rule for ${source}`);
  return Object.fromEntries(rule.headers.map((h) => [h.key, h.value]));
};
const FINGERPRINT = /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/;
const DOMAIN = 'skipti.vardrlabs.com';

test('the join page runs only its one pinned script and loads nothing from anywhere', () => {
  const scripts = [...page.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)];
  assert.equal(scripts.length, 1);
  assert.equal(scripts[0][1].trim(), '', 'inline, no src');
  const hash = createHash('sha256').update(scripts[0][2], 'utf8').digest('base64');
  // Re-derived in Python for D-159 and again for D-199: EFFNKyFe…NK0=.
  assert.equal(hash, 'EFFNKyFe2EdoRLOiKfYH+4N8e7wmD/iLcH9toZ+3NK0=');
  for (const source of ['/join', '/join/**']) {
    assert.ok(headersFor(source)['Content-Security-Policy'].includes(`script-src 'sha256-${hash}'`), source);
  }
  for (const html of [page, missing]) {
    assert.doesNotMatch(html, /\s(src|href)\s*=|<link|@import|url\(/i, 'no external resource');
  }
  assert.doesNotMatch(missing, /<script/i, 'the 404 page has no script');
});

test('every path gets one policy: the join page its own, everything else none that runs code', () => {
  assert.equal(config.public, 'public');
  assert.deepEqual(config.rewrites, [
    { source: '/join', destination: '/join/index.html' },
    { source: '/privacy', destination: '/privacy/index.html' },
  ]);
  assert.equal(config.headers[0].source, '**', 'the catch-all first, so later rules replace it');
  const all = headersFor('**');
  assert.match(all['Content-Security-Policy'], /^default-src 'none';/);
  assert.doesNotMatch(all['Content-Security-Policy'], /script-src/);
  for (const source of ['**', '/join', '/join/**']) {
    const h = headersFor(source);
    assert.equal(h['Referrer-Policy'], 'no-referrer', source);
    assert.match(h['Content-Security-Policy'], /frame-ancestors 'none'/, source);
  }
  assert.equal(headersFor('/join')['Content-Security-Policy'], headersFor('/join/**')['Content-Security-Policy']);
  for (const source of ['/.well-known/apple-app-site-association', '/.well-known/assetlinks.json']) {
    assert.equal(headersFor(source)['Content-Type'], 'application/json', source);
  }
  assert.ok(!config.ignore.some((g) => g.includes('.*')), 'dot folders (.well-known) are deployed');
});

test('the iOS app-link file names exactly this app, for /join only, as the app declares', () => {
  const project = read('../../ios/Skipti.xcodeproj/project.pbxproj');
  const team = [...new Set([...project.matchAll(/DEVELOPMENT_TEAM = (\w+);/g)].map((m) => m[1]))];
  assert.deepEqual(team, ['27GK2T7UCT']);
  assert.ok(project.includes('PRODUCT_BUNDLE_IDENTIFIER = com.vardrlabs.skipti;'));
  assert.deepEqual(Object.keys(aasa), ['applinks'], 'no webcredentials since passkeys left (D-192)');
  assert.deepEqual(aasa.applinks.details.map((d) => d.appIDs), [['27GK2T7UCT.com.vardrlabs.skipti']]);
  assert.deepEqual(aasa.applinks.details[0].components.map((c) => c['/']), ['/join']);
  const entitlements = read('../../ios/Skipti/Skipti.entitlements');
  const domains = [...entitlements.matchAll(/<string>((applinks|webcredentials):[^<]+)<\/string>/g)].map((m) => m[1]);
  assert.deepEqual(domains, [`applinks:${DOMAIN}`]);
});

test('the Android app-link file names exactly this app and its signing keys, for every link the app claims', () => {
  assert.equal(links.length, 1);
  const [only] = links;
  assert.deepEqual(only.relation, ['delegate_permission/common.handle_all_urls']);
  assert.equal(only.target.namespace, 'android_app');
  const gradle = read('../../android/app/build.gradle.kts');
  assert.equal(only.target.package_name, gradle.match(/applicationId = "([^"]+)"/)[1]);
  const prints = only.target.sha256_cert_fingerprints;
  // D-199, corrected 2026-10-13: Play's current key, its previous key (installs from before the rotation), upload.
  assert.equal(prints.length, 3);
  assert.equal(new Set(prints).size, 3);
  for (const p of prints) assert.match(p, FINGERPRINT);
  const manifest = read('../../android/app/src/main/AndroidManifest.xml');
  assert.match(manifest, new RegExp(`android:autoVerify="true">[\\s\\S]*?android:host="${DOMAIN.replace(/\./g, '\\.')}" android:path="/join"`));
});

test("the README's fingerprints are the ones the domain serves", () => {
  const readme = read('../../README.md');
  const listed = [...readme.matchAll(/`((?:[0-9A-F]{2}:){31}[0-9A-F]{2})`/g)].map((m) => m[1]);
  assert.deepEqual(listed.sort(), [...links[0].target.sha256_cert_fingerprints].sort());
});

test('the privacy policy is one plain page: no script, nothing from elsewhere, versioned, filled in', () => {
  assert.doesNotMatch(privacy, /<script/i);
  assert.doesNotMatch(privacy, /\s(src|href)\s*=|<link|@import|url\(/i, 'no external resource');
  assert.match(privacy, /Version \d+, \d{1,2} [A-Z][a-z]+ \d{4}\./, 'versioned and dated (SPEC §12.3)');
  assert.doesNotMatch(privacy, /\{\{|\}\}/, 'no placeholder left');
  // D-244 (ADR-0043): the settings line the founder chose, and the version it brought (his pop-up, 2026-10-20).
  assert.ok(privacy.includes('<li>Your app settings: theme, light or dark, text size, Less motion, haptics, app icon and default currency, so your other phones match.</li>'), 'the settings line');
  assert.ok(privacy.includes('Version 2, 20 October 2026.'), 'version 2 with the settings line');
  // The strict catch-all CSP (no script) is the one it is served with.
  assert.ok(!config.headers.some((h) => h.source.startsWith('/privacy')), 'no looser policy for the page');
  assert.match(privacy, /not end to end/, 'states its own limit (SPEC §14.6)');
});
