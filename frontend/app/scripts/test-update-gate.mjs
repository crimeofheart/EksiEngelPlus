// Offline checks for the update gate (assets/js/updateGate.js): signature
// verification, the gate rules, and parity with the Android mirror. Policies are
// signed here with throwaway keys; fetch and storage are fakes.
import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
globalThis.chrome = { storage: { local: { get: async () => ({}), set: async () => {} } } };
const gate = await import('../assets/js/updateGate.js');

let tests = 0;
async function check(name, fn) {
  await fn();
  tests++;
  console.log(`ok: ${name}`);
}

function keyPair() {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return { privateKey, spki: publicKey.export({ type: 'spki', format: 'der' }).toString('base64') };
}

/** An envelope as tools/set-min-version.sh writes it: DER signature over the payload bytes. */
function envelope(payload, privateKey) {
  const bytes = Buffer.from(JSON.stringify(payload));
  return JSON.stringify({
    alg: 'ES256',
    payload: bytes.toString('base64'),
    sig: sign('sha256', bytes, privateKey).toString('base64'),
  });
}

function policy(channels, extra = {}) {
  return { v: 1, app: gate.APP_ID, issuedAt: 1000, channels, message: {}, ...extra };
}

const primary = keyPair();
const backup = keyPair();
const keys = [primary.spki, backup.spki];

await check('version codes follow the Android versionCode', () => {
  assert.equal(gate.versionCode('0.6.4'), 604);
  assert.equal(gate.versionCode('1.0.0'), 10000);
  assert.equal(gate.versionCode('0.12.3'), 1203);
  assert.equal(gate.versionCode('garbage'), 0);
});

await check('the channel is read from the manifest variant', () => {
  assert.equal(gate.channelOf(JSON.parse(readFileSync(path.join(ROOT, 'frontend/app/manifest.chrome.json')))), 'chrome');
  assert.equal(gate.channelOf(JSON.parse(readFileSync(path.join(ROOT, 'frontend/app/manifest.firefox.json')))), 'firefox');
});

await check('DER signatures convert to raw r||s, padded and unpadded', () => {
  for (let i = 0; i < 40; i++) {
    const { privateKey } = keyPair();
    const der = sign('sha256', Buffer.from(`m${i}`), privateKey);
    const raw = gate.derToRaw(new Uint8Array(der));
    assert.equal(raw?.length, 64);
  }
  assert.equal(gate.derToRaw(new Uint8Array([0x30, 0x02, 0x02, 0x00])), null);
  assert.equal(gate.derToRaw(new Uint8Array([1, 2, 3])), null);
});

await check('a signed policy verifies with either built-in key, nothing else does', async () => {
  const body = policy({ chrome: { minVersionCode: 605 } });
  assert.equal((await gate.verifyPolicy(envelope(body, primary.privateKey), gate.APP_ID, keys)).channels.chrome.minVersionCode, 605);
  assert.ok(await gate.verifyPolicy(envelope(body, backup.privateKey), gate.APP_ID, keys));
  assert.equal(await gate.verifyPolicy(envelope(body, keyPair().privateKey), gate.APP_ID, keys), null, 'stranger key');

  const forged = JSON.parse(envelope(body, primary.privateKey));
  forged.payload = Buffer.from(JSON.stringify(policy({ chrome: { minVersionCode: 9999 } }))).toString('base64');
  assert.equal(await gate.verifyPolicy(JSON.stringify(forged), gate.APP_ID, keys), null, 'altered payload');

  assert.equal(await gate.verifyPolicy(envelope({ ...body, app: 'com.other' }, primary.privateKey), gate.APP_ID, keys), null, 'other app');
  assert.equal(await gate.verifyPolicy(JSON.stringify({ alg: 'none', payload: forged.payload, sig: '' }), gate.APP_ID, keys), null, 'unsigned');
  assert.equal(await gate.verifyPolicy('not json', gate.APP_ID, keys), null);
});

const at = (issuedAt, channels, extra) => gate.parsePolicy(JSON.stringify(policy(channels, { issuedAt, ...extra })));

await check('a raised minimum blocks older builds and only on its channel', () => {
  const state = gate.accept({}, at(1, { chrome: { minVersionCode: 605 }, firefox: { minVersionCode: 0 } }), 'chrome', 0);
  assert.deepEqual(gate.evaluate(state, 604), { kind: 'required', blocks: true, minimum: 605 });
  assert.equal(gate.evaluate(state, 605).blocks, false);
  const firefox = gate.accept({}, at(1, { chrome: { minVersionCode: 605 }, firefox: { minVersionCode: 0 } }), 'firefox', 0);
  assert.equal(gate.evaluate(firefox, 604).blocks, false);
});

await check('a revocation stops one build and can be lifted', () => {
  const revoked = gate.accept({}, at(1, { chrome: { minVersionCode: 0, revokedVersionCodes: [605] } }), 'chrome', 0);
  assert.equal(gate.evaluate(revoked, 605).kind, 'revoked');
  assert.equal(gate.evaluate(revoked, 604).blocks, false);
  assert.equal(gate.evaluate(revoked, 606).blocks, false);
  const lifted = gate.accept(revoked, at(2, { chrome: { minVersionCode: 0 } }), 'chrome', 0);
  assert.equal(gate.evaluate(lifted, 605).blocks, false);
});

await check('the minimum never goes down, and an older policy changes nothing', () => {
  const raised = gate.accept({}, at(5, { chrome: { minVersionCode: 605 } }), 'chrome', 0);
  const lowered = gate.accept(raised, at(6, { chrome: { minVersionCode: 0 } }), 'chrome', 0);
  assert.equal(gate.evaluate(lowered, 604).blocks, true);
  const replayed = gate.accept(raised, at(4, { chrome: { minVersionCode: 0, revokedVersionCodes: [700] } }), 'chrome', 0);
  assert.deepEqual(replayed, raised);
});

await check('an expired policy is ignored', () => {
  const expired = at(1, { chrome: { minVersionCode: 605 } }, { expiresAt: 2 });
  assert.equal(gate.evaluate(gate.accept({}, expired, 'chrome', 3000), 604).blocks, false);
  assert.equal(gate.evaluate(gate.accept({}, expired, 'chrome', 1000), 604).blocks, true);
});

await check('the maintainer message is shown, Turkish first, with a default', () => {
  const decision = { kind: 'required' };
  assert.equal(gate.blockingMessage({ messages: { en: 'update', tr: 'güncelle' } }, decision), 'güncelle');
  assert.equal(gate.blockingMessage({ messages: { en: 'update' } }, decision), 'update');
  assert.match(gate.blockingMessage({}, decision), /güncelleyin/);
});

/** A gate over an in-memory store and a fake server answering [answer]. */
function fakeGate(answer, version = '0.6.4', manifestExtra = {}) {
  const store = {};
  let now = 10_000_000;
  let fetched = 0;
  const instance = new gate.UpdateGate({
    storage: { get: async (key) => ({ [key]: store[key] }), set: async (items) => Object.assign(store, items) },
    manifest: { version, ...manifestExtra },
    now: () => now,
    fetch: async () => { fetched++; return answer(); },
  });
  return { instance, tick: (ms) => { now += ms; }, fetches: () => fetched };
}

await check('refresh: unreachable server blocks nothing; a signed minimum blocks; checks are spaced', async () => {
  const offline = fakeGate(() => { throw new TypeError('network down'); });
  assert.equal(await offline.instance.refresh(), false);
  assert.equal(await offline.instance.blocks(), false);

  // The real built-in keys are not the test's, so verify through the module's
  // own path by stubbing the key list via a signed policy from a known key.
  const signedBody = envelope(policy({ chrome: { minVersionCode: 605 } }), primary.privateKey);
  const original = gate.PUBLIC_KEYS.slice();
  gate.PUBLIC_KEYS.splice(0, gate.PUBLIC_KEYS.length, ...keys);
  try {
    const online = fakeGate(() => new Response(signedBody, { status: 200 }));
    assert.equal(await online.instance.refresh(), true);
    const decision = await online.instance.decision();
    assert.equal(decision.blocks, true);
    assert.match(decision.message, /güncelleyin/);
    assert.equal(await online.instance.refresh(), false, 'not due again yet');
    assert.equal(online.fetches(), 1);
    online.tick(gate.CHECK_INTERVAL_MS);
    await online.instance.refresh();
    assert.equal(online.fetches(), 2);
    assert.equal(await online.instance.refresh({ force: true }), true);

    const missing = fakeGate(() => new Response('', { status: 404 }));
    assert.equal(await missing.instance.refresh(), false);
    assert.equal(await missing.instance.blocks(), false);
  } finally {
    gate.PUBLIC_KEYS.splice(0, gate.PUBLIC_KEYS.length, ...original);
  }
});

await check('the built-in keys match the Android mirror, in the same order', () => {
  const kotlin = readFileSync(path.join(ROOT, 'android/app/src/main/kotlin/org/duzgun/eksiengelplus/gate/AppPolicy.kt'), 'utf8');
  const android = [...kotlin.matchAll(/"(MFkw[A-Za-z0-9+/=]+)"/g)].map((m) => m[1]);
  assert.deepEqual(android, gate.PUBLIC_KEYS);
  assert.equal(gate.PUBLIC_KEYS.length, 2);
});

await check('the last published policy verifies under the built-in keys', async () => {
  const file = path.join(ROOT, 'backend/policy/app-v1.json');
  if (!existsSync(file)) return;
  const published = await gate.verifyPolicy(readFileSync(file, 'utf8'));
  assert.ok(published, 'backend/policy/app-v1.json does not verify');
  for (const channel of ['github', 'play', 'chrome', 'firefox']) {
    assert.ok(published.channels[channel], `no ${channel} channel`);
  }
});

console.log(`${tests} update gate checks passed`);
