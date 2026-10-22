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
// D-254: hosting is two targets, `main` (the invite domain, as before) and `admin` (the approval page's own site).
const hosting = JSON.parse(read('../firebase.json')).hosting;
const config = hosting.find((t) => t.target === 'main');
const admin = hosting.find((t) => t.target === 'admin');
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
  assert.ok(privacy.includes('Version 3, 21 October 2026.'), 'version 3 with the approval line (D-254, decision 235)');
  assert.ok(privacy.includes('<li>Approval: while SKIPTI is in early testing, a new account waits for our approval before anything else is kept in the cloud. To decide, we see the name you show others, your sign-in email if Apple or Google gave one, and whether you use an iPhone or an Android phone.</li>'), 'the approval line');
  // The strict catch-all CSP (no script) is the one it is served with.
  assert.ok(!config.headers.some((h) => h.source.startsWith('/privacy')), 'no looser policy for the page');
  assert.match(privacy, /not end to end/, 'states its own limit (SPEC §14.6)');
});

// ---- D-254: the admin page's own Hosting site (decisions 228, 229; design section 6). ----

const adminPage = read('../admin/index.html');
const adminMissing = read('../admin/404.html');
const adminScripts = [...adminPage.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)];
const adminScript = adminScripts.length === 1 ? adminScripts[0][2] : '';
const shellKotlin = read('../../android/app/src/main/java/com/vardrlabs/skipti/FirebaseConfig.kt');
const shellSwift = read('../../ios/Skipti/App/FirebaseConfig.swift');
const constant = (name) => {
  const found = [...adminScript.matchAll(new RegExp(`const ${name} = "([^"]*)";`, 'g'))];
  assert.equal(found.length, 1, `one ${name} in the admin page`);
  return found[0][1];
};

test('hosting is exactly two targets, main and admin, mapped to their sites in .firebaserc', () => {
  assert.ok(Array.isArray(hosting));
  assert.deepEqual(hosting.map((t) => t.target), ['main', 'admin']);
  assert.equal(admin.public, 'admin');
  const rc = JSON.parse(read('../.firebaserc'));
  assert.equal(rc.projects.default, 'skipti-26');
  assert.deepEqual(rc.targets, { 'skipti-26': { hosting: { main: ['skipti-26'], admin: ['skipti-26-admin'] } } });
  // The admin site serves only its own folder: none of the invite domain's files, rewrites or app links.
  assert.equal(admin.rewrites, undefined);
  assert.equal(admin.redirects, undefined);
});

test('the admin site sends one strict policy on every path, its script pinned by hash', () => {
  assert.deepEqual(admin.headers.map((h) => h.source), ['**'], 'one rule, every path, nothing loosens it');
  const h = Object.fromEntries(admin.headers[0].headers.map((x) => [x.key, x.value]));
  assert.deepEqual(Object.keys(h).sort(), ['Cache-Control', 'Content-Security-Policy', 'Referrer-Policy',
    'X-Content-Type-Options', 'X-Robots-Tag']);
  assert.equal(adminScripts.length, 1, 'exactly one script');
  assert.equal(adminScripts[0][1].trim(), '', 'inline, no src');
  const hash = createHash('sha256').update(adminScript, 'utf8').digest('base64');
  assert.equal(h['Content-Security-Policy'],
    `default-src 'none'; script-src 'sha256-${hash}'; style-src 'unsafe-inline'; ` +
    'connect-src https://identitytoolkit.googleapis.com https://us-central1-skipti-26.cloudfunctions.net; ' +
    "img-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
  assert.equal(h['Referrer-Policy'], 'no-referrer');
  assert.equal(h['X-Content-Type-Options'], 'nosniff');
  assert.equal(h['X-Robots-Tag'], 'noindex, nofollow');
  assert.equal(h['Cache-Control'], 'no-store');
});

test('the admin pages load nothing, and the script reaches only Google sign-in, Auth and the callable', () => {
  const markup = adminPage.replace(adminScripts[0][0], '');
  for (const html of [markup, adminMissing]) {
    assert.doesNotMatch(html, /\s(src|href|action)\s*=|<link|<img|<iframe|<form|@import|url\(/i, 'no external resource');
    assert.doesNotMatch(html, /\son[a-z]+\s*=/i, 'no inline handler');
  }
  assert.doesNotMatch(adminMissing, /<script/i, 'the 404 page has no script');
  const origins = new Set([...adminPage.matchAll(/https?:\/\/[^\s"'`<>)\\]+/g)].map((m) => new URL(m[0]).origin));
  assert.deepEqual([...origins].sort(), [
    'http://127.0.0.1:5001', 'https://accounts.google.com',
    'https://identitytoolkit.googleapis.com', 'https://securetoken.google.com',
    'https://us-central1-skipti-26.cloudfunctions.net',
  ]);
  // securetoken.google.com is never fetched: it is the emulator token's `iss` claim, its only occurrence.
  assert.deepEqual([...adminPage.matchAll(/.{6}https:\/\/securetoken\.google\.com/g)].map((m) => m[0]),
    ['iss: "https://securetoken.google.com']);
  // User data never reaches HTML parsing (names and emails come from other people) or lasting storage.
  assert.doesNotMatch(adminScript, /innerHTML|outerHTML|insertAdjacentHTML|document\.write|eval\(|new Function/);
  assert.doesNotMatch(adminScript, /localStorage|indexedDB|document\.cookie/);
  assert.match(adminScript, /history\.replaceState\(null, "", location\.pathname\)/, 'the fragment is cleared');
  assert.match(adminScript, /sessionStorage\.removeItem\(ROUND_TRIP\)/, 'the round trip is removed on return');
});

test('emulator mode exists only behind the localhost check', () => {
  const guard = 'const LOCAL = location.hostname === "localhost" || location.hostname === "127.0.0.1";';
  assert.equal(adminScript.split(guard).length, 2, 'the guard, once');
  assert.equal([...adminScript.matchAll(/\bLOCAL\s*=/g)].length, 1, 'nothing else sets it');
  // The Functions emulator's URL is the true arm of the LOCAL conditional, production's its other arm. Sign-in has
  // no emulator arm: emulator mode builds an unsigned token itself, and only behind the check.
  assert.match(adminScript, /const SIGN_IN = "https:\/\/identitytoolkit\.googleapis\.com\/v1\/accounts:signInWithIdp\?key=" \+ API_KEY;/);
  assert.match(adminScript, /const CALLABLE = LOCAL\s*\? "http:\/\/127\.0\.0\.1:5001\/skipti-26\/us-central1\/adminApprovals"\s*: "https:\/\/us-central1-skipti-26\.cloudfunctions\.net\/adminApprovals";/);
  assert.equal([...adminScript.matchAll(/127\.0\.0\.1:\d+/g)].length, 1, 'no other emulator address');
  assert.match(adminScript, /function startEmulator\(\) \{\s*if \(!LOCAL\) return;/, 'the unsigned token is built only on localhost');
  assert.equal([...adminScript.matchAll(/alg: "none"/g)].length, 1, 'one unsigned token, in startEmulator');
  assert.ok(adminScript.indexOf('alg: "none"') > adminScript.indexOf('function startEmulator()'));
  // Its claims are the Functions emulator tests' own (functions.test.mjs idToken): the typed id is sub and user_id.
  assert.match(adminScript, /iss: "https:\/\/securetoken\.google\.com\/" \+ PROJECT, aud: PROJECT, iat: now, exp: now \+ 3600, auth_time: now,\s*sub: uid, user_id: uid, firebase: \{ sign_in_provider: "google\.com", identities: \{\} \},/);
  assert.match(adminPage, /<input id="test-uid" type="text" value="emulator-admin"/, 'prefilled with the emulator admin id');
  // The test-uid form is shown only in emulator mode, and the Google token is only accepted off it.
  assert.match(adminScript, /if \(LOCAL\) \{\s*\$\("local"\)\.hidden = false;\s*\$\("google-sign-in"\)\.hidden = true;\s*\$\("emulator-sign-in"\)\.hidden = false;\s*\}/);
  assert.match(adminPage, /<div class="field" id="emulator-sign-in" hidden>/, 'hidden until the check shows it');
  assert.match(adminScript, /if \(!LOCAL && fragment && /);
});

test("the admin page signs in with the apps' own public values, and checks the round trip", () => {
  assert.equal(constant('API_KEY'), shellKotlin.match(/const val API_KEY = "([^"]+)"/)[1]);
  assert.match(constant('API_KEY'), /^AIza/);
  assert.equal(constant('WEB_CLIENT_ID'), shellKotlin.match(/const val WEB_CLIENT_ID = "([^"]+)"/)[1]);
  const project = shellKotlin.match(/const val PROJECT_ID = "([^"]+)"/)[1];
  assert.equal(project, shellSwift.match(/static let projectID = "([^"]+)"/)[1]);
  assert.equal(constant('PROJECT'), project);
  assert.ok(adminScript.includes(`https://us-central1-${project}.cloudfunctions.net/adminApprovals`));
  assert.ok(adminScript.includes(`http://127.0.0.1:5001/${project}/us-central1/adminApprovals`));
  assert.equal(constant('GOOGLE_AUTH'), 'https://accounts.google.com/o/oauth2/v2/auth');
  assert.match(adminScript, /response_type: "id_token",/);
  assert.match(adminScript, /scope: "openid email",/);
  assert.match(adminScript, /redirect_uri: location\.origin \+ "\/",/);
  assert.match(adminScript, /params\.get\("state"\) !== saved\.state/, 'state checked');
  assert.match(adminScript, /claims\.nonce !== saved\.nonce/, 'nonce checked');
  assert.match(adminScript, /crypto\.getRandomValues/, 'state and nonce are random');
});

test('the admin pages are public text: no em or en dash', () => {
  for (const html of [adminPage, adminMissing]) assert.doesNotMatch(html, /[\u2013\u2014]|\\u201[34]/);
});

// D-254 fix wave M1: the migration runs again whenever asked, so its banner
// shows while any profile has no decision (the list's toMigrate), never only
// until the first run's marker exists.
test('the migration banner shows while profiles wait for it, not until the first run', () => {
  assert.match(adminScript, /\$\("migrate"\)\.hidden = n === 0;/);
  assert.doesNotMatch(adminScript, /r\.migrated/, 'the marker decides nothing on the page');
});
