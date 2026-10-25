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
// Code scanning (CodeQL js/bad-tag-filter, js/incomplete-sanitization, 2026-10-25): script tags are matched in any case
// and with a space before the closing `>`, every `<script` opening is counted, and text put into a RegExp has every
// special character escaped.
const SCRIPT_TAG = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;
const scriptOpenings = (html) => (html.match(/<script\b/gi) || []).length;
const escapeRegExp = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

test('the join page runs only its one pinned script and loads nothing from anywhere', () => {
  const scripts = [...page.matchAll(SCRIPT_TAG)];
  assert.equal(scripts.length, 1);
  assert.equal(scriptOpenings(page), 1, 'no other script tag, in any case or form');
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
  assert.match(manifest, new RegExp(`android:autoVerify="true">[\\s\\S]*?android:host="${escapeRegExp(DOMAIN)}" android:path="/join"`));
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
  assert.ok(privacy.includes('Version 4, 24 October 2026.'), 'version 4: the sign-in email as each phone really sends it (decision 328)');
  // Decision 328 (2026-10-24): iPhone asks Apple and Google for no email (ADR-0035, scope openid); Android's Google
  // sign-in token carries it. The page says exactly that, and never that every sign-in gives us an email.
  assert.ok(privacy.includes('On an Android phone, Google also tells us the\n  email address of the Google account you choose; on an iPhone we ask for no email and get none.'), 'the sign-in email line');
  assert.doesNotMatch(privacy, /they tell us an account id and the email address/, 'not the Version 3 claim');
  assert.ok(privacy.includes('<li>Approval: while SKIPTI is in early testing, a new account waits for our approval before anything else is kept in the cloud. To decide, we see the name you show others, your sign-in email if Apple or Google gave one, and whether you use an iPhone or an Android phone.</li>'), 'the approval line');
  // The strict catch-all CSP (no script) is the one it is served with.
  assert.ok(!config.headers.some((h) => h.source.startsWith('/privacy')), 'no looser policy for the page');
  assert.match(privacy, /not end to end/, 'states its own limit (SPEC §14.6)');
});

// ---- D-254: the admin page's own Hosting site (decisions 228, 229; design section 6). ----

const adminPage = read('../admin/index.html');
const adminMissing = read('../admin/404.html');
const adminScripts = [...adminPage.matchAll(SCRIPT_TAG)];
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
  assert.equal(scriptOpenings(adminPage), 1, 'no other script tag, in any case or form');
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

// ---- D-254 look fixes 4 and 5: the admin page's script, run. A small stand-in DOM (no dependency) holds the page's
// elements by id; the script runs in its own context on localhost (emulator mode), signs in, and lists `LISTED`. ----

class Node {
  constructor(tag, id = null, hidden = false) {
    Object.assign(this, { tag, id, hidden, children: [], attrs: {}, dataset: {}, listeners: {}, className: '', type: '',
      disabled: false, open: false, value: '' });
    this._text = '';
  }
  get textContent() { return this._text + this.children.map((c) => c.textContent).join(''); }
  set textContent(t) { this._text = String(t); this.children = []; }
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this._text = ''; this.children = nodes; }
  setAttribute(k, v) { this.attrs[k] = String(v); }
  getAttribute(k) { return this.attrs[k] ?? null; }
  addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); }
  fire(type) { for (const fn of this.listeners[type] ?? []) fn.call(this, { type, key: '' }); }
  focus() {}
  all() { return [this, ...this.children.flatMap((c) => c.all())]; }
}

const MIN = 60000;
const NOW = Date.UTC(2026, 9, 21, 12, 0, 0);
const LISTED = {
  pending: [
    { uid: 'aaaaaaaaaaaaaaaaaaaaaaaaaaa111', state: 'pending', requestedAt: NOW - 30000, name: 'Al', platform: 'ios' },
    { uid: 'bbbbbbbbbbbbbbbbbbbbbbbbbbb222', state: 'pending', requestedAt: NOW - 40000, name: 'Al', platform: 'ios' },
    { uid: 'ccccccccccccccccccccccccccc333', state: 'pending', requestedAt: NOW - 50000, platform: 'android' },
    { uid: 'ddddddddddddddddddddddddddd444', state: 'pending', requestedAt: NOW - 50000, platform: 'android' },
  ],
  approved: [{ uid: 'eeeeeeeeeeeeeeeeeeeeeeeeeee555', state: 'approved', requestedAt: NOW - 9 * MIN, decidedAt: NOW - 30000, decidedBy: 'admin', platform: 'ios' }],
  denied: [{ uid: 'fffffffffffffffffffffffffff666', state: 'denied', requestedAt: NOW - 9 * MIN, decidedAt: NOW - 30000, decidedBy: 'admin', platform: 'android' }],
  migrated: true, toMigrate: 0,
};

async function runAdminPage() {
  const byId = new Map();
  for (const m of adminPage.replace(adminScripts[0][0], '').matchAll(/<([a-z0-9]+)\b([^>]*)\bid="([^"]+)"([^>]*)>/g)) {
    const node = new Node(m[1], m[3], /\shidden\b/.test(m[2] + m[4]));
    const value = (m[2] + m[4]).match(/\svalue="([^"]*)"/);
    if (value) node.value = value[1];
    byId.set(m[3], node);
  }
  const made = [];
  const clock = { now: NOW };
  const timers = [];
  class FakeDate extends Date {
    constructor(...a) { super(...(a.length ? a : [clock.now])); }
    static now() { return clock.now; }
  }
  const store = new Map();
  const context = {
    document: {
      getElementById: (id) => byId.get(id) ?? null,
      createElement: (tag) => { const n = new Node(tag); made.push(n); return n; },
      querySelectorAll: () => [...made.filter((n) => n.dataset.act), byId.get('migrate-go'), byId.get('refresh')],
    },
    location: { hostname: 'localhost', hash: '', pathname: '/', origin: 'http://localhost:5002', assign() {} },
    history: { replaceState() {} },
    sessionStorage: { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, v), removeItem: (k) => store.delete(k) },
    navigator: { clipboard: { writeText: () => Promise.resolve() } },
    fetch: async () => ({ ok: true, json: async () => ({ result: structuredClone(LISTED) }) }),
    setInterval: (fn, ms) => { timers.push({ fn, ms }); return timers.length; },
    clearInterval() {},
    Date: FakeDate, TextEncoder, TextDecoder, URLSearchParams, btoa, atob, crypto: globalThis.crypto,
  };
  const { runInNewContext } = await import('node:vm');
  runInNewContext(adminScript, context);
  byId.get('emulator').fire('click');
  for (let i = 0; i < 10; i += 1) await new Promise((r) => setImmediate(r));
  assert.equal(byId.get('view-list').hidden, false, 'the list is shown');
  return { byId, clock, timers, buttons: made.filter((n) => n.dataset.act) };
}

const metaLines = (box) => box.all().filter((n) => n.className === 'meta').map((n) => n.textContent);

test('D-254 look fix 4: every Approve and Deny button has a label no other button shares', async () => {
  const { buttons } = await runAdminPage();
  assert.equal(buttons.length, 4 * 2 + 1 + 1);
  const labels = buttons.map((b) => b.getAttribute('aria-label'));
  assert.equal(new Set(labels).size, labels.length, `unique: ${JSON.stringify(labels)}`);
  assert.ok(labels.includes('Approve Al, iPhone, account ending aaa111'));
  assert.ok(labels.includes('Deny Al, iPhone, account ending bbb222'));
  assert.ok(labels.includes('Approve No name yet, Android, account ending ccc333'));
  assert.ok(labels.includes('Deny No name yet, Android, account ending ddd444'));
  assert.ok(labels.includes('Deny No name yet, iPhone, account ending eee555'));
  assert.ok(labels.includes('Approve No name yet, Android, account ending fff666'));
});

test('D-254 look fix 5: relative times are worked out again every minute, and when a folded list opens', async () => {
  const { byId, clock, timers } = await runAdminPage();
  assert.deepEqual(metaLines(byId.get('waiting')).map((t) => t.split(' · ').pop()),
    ['asked just now', 'asked just now', 'asked just now', 'asked just now']);
  assert.match(metaLines(byId.get('approved'))[0], /approved just now$/);
  const minute = timers.filter((t) => t.ms === MIN);
  assert.equal(minute.length, 1, 'one timer, every minute');
  clock.now += 5 * MIN;
  minute[0].fn();
  assert.match(metaLines(byId.get('waiting'))[0], /asked 5 min ago$/, 'the minute timer');
  assert.match(metaLines(byId.get('approved'))[0], /approved 5 min ago$/);
  // Between ticks, opening a fold brings its times up to date; closing it does nothing.
  clock.now += 2 * MIN;
  const fold = byId.get('denied-fold');
  fold.open = false;
  fold.fire('toggle');
  assert.match(metaLines(byId.get('denied'))[0], /denied 5 min ago$/, 'closing does not re-render');
  fold.open = true;
  fold.fire('toggle');
  assert.match(metaLines(byId.get('denied'))[0], /denied 7 min ago$/, 'opening the fold');
  byId.get('approved-fold').open = true;
  clock.now += MIN;
  byId.get('approved-fold').fire('toggle');
  assert.match(metaLines(byId.get('approved'))[0], /approved 8 min ago$/, 'the other fold too');
});
