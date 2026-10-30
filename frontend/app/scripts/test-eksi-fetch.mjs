// Offline checks for the route to Ekşi Sözlük when Cloudflare challenges the
// background: eksiFetch.js on one side, answerEksiFetch in script.js on the
// other. fetch and chrome.tabs are fakes.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const APP_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');

class FakeTabs {
  constructor() { this.tabs = []; this.sent = []; this.created = []; this.removed = []; this.nextId = 100; }
  add(respond, { status = 'complete', url = 'https://eksisozluk.com/gundem' } = {}) {
    const tab = { id: this.nextId++, url, status, respond };
    this.tabs.push(tab);
    return tab;
  }
  query = async ({ url }) => this.tabs
    .filter(t => t.url.startsWith(url.slice(0, -1)))
    .map(({ id, url: u, status }) => ({ id, url: u, status, discarded: false }));
  create = async ({ url, active }) => {
    this.created.push({ url, active });
    const tab = this.add(this.onCreate, { status: 'loading', url });
    setTimeout(() => { tab.status = 'complete'; }, 5);
    return { id: tab.id };
  };
  sendMessage = async (id, message) => {
    this.sent.push({ id, message });
    const tab = this.tabs.find(t => t.id === id);
    if (!tab) throw new Error('No tab with id');
    return tab.respond(message);
  };
  remove = async (id) => { this.removed.push(id); this.tabs = this.tabs.filter(t => t.id !== id); };
}

let tabs = new FakeTabs();
globalThis.chrome = {
  storage: { local: { get: async () => ({}), set: async () => {} } },
  get tabs() { return tabs; },
};
const { log } = await import('../assets/js/log.js');
log.logConsole = false;
const { eksiFetch, resetRoute, tabRoute } = await import('../assets/js/eksiFetch.js');
Object.assign(tabRoute, { retryMs: 1, clickAfterMs: 15, giveUpAfterMs: 60, closeIdleAfterMs: 20 });

const URL_ = 'https://eksisozluk.com/biri/ssg';
const challenge = () => new Response('', { status: 403, headers: { 'cf-mitigated': 'challenge' } });
const page = (text = 'page') => ({ status: 200, headers: { 'content-type': 'text/html' }, text });
const challengeAnswer = { status: 403, headers: { 'cf-mitigated': 'challenge' }, text: '' };
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

let direct = 0;
function directly(answer) {
  direct = 0;
  globalThis.fetch = async () => { direct++; return answer(); };
}

let tests = 0;
async function check(name, fn) {
  tabs = new FakeTabs();
  resetRoute();
  tabRoute.onCheckWaiting = null;
  await fn();
  tests++;
  console.log(`ok: ${name}`);
}

await check('requests stay direct while nothing is challenged', async () => {
  directly(() => new Response('ok', { status: 200 }));
  tabs.add(() => page());
  const response = await eksiFetch(URL_);
  assert.equal(await response.text(), 'ok');
  assert.equal(direct, 1);
  assert.equal(tabs.sent.length, 0);
});

await check('a challenge goes through an open Ekşi tab, which keeps the route until it is reset', async () => {
  directly(challenge);
  const tab = tabs.add(() => page('through the tab'));
  const init = { method: 'POST', headers: { 'x-requested-with': 'XMLHttpRequest' }, body: 'id=5' };
  const response = await eksiFetch('https://eksisozluk.com/userrelation/addrelation/5?r=m', init);
  assert.equal(response.status, 200);
  assert.equal(await response.text(), 'through the tab');
  assert.deepEqual(tabs.sent[0], { id: tab.id, message: {
    action: 'eksiFetch', url: 'https://eksisozluk.com/userrelation/addrelation/5?r=m', ...init,
  } });
  await eksiFetch(URL_);
  assert.equal(direct, 1, 'the second request skips the direct route');
  resetRoute();
  directly(() => new Response('ok', { status: 200 }));
  await eksiFetch(URL_);
  assert.equal(direct, 1, 'after a reset the direct route is tried again');
});

await check('a 429 and its Retry-After come back from the tab as they would directly', async () => {
  directly(challenge);
  tabs.add(() => ({ status: 429, headers: { 'retry-after': '65' }, text: '' }));
  const response = await eksiFetch(URL_);
  assert.equal(response.status, 429);
  assert.equal(response.headers.get('Retry-After'), '65');
});

await check('a tab that does not answer is skipped for the next', async () => {
  directly(challenge);
  tabs.add(() => { throw new Error('Receiving end does not exist'); });
  const second = tabs.add(() => page());
  assert.equal((await eksiFetch(URL_)).status, 200);
  assert.equal(tabs.sent.at(-1).id, second.id);
});

await check('with no Ekşi tab, one is opened in the background, waited for, and closed once idle', async () => {
  directly(challenge);
  let solving = 3;
  tabs.onCreate = () => (solving-- > 0 ? challengeAnswer : page());
  const response = await eksiFetch(URL_);
  assert.equal(response.status, 200);
  assert.deepEqual(tabs.created, [{ url: 'https://eksisozluk.com/', active: false }]);
  await eksiFetch(URL_);
  assert.equal(tabs.created.length, 1, 'never a second tab');
  await sleep(tabRoute.closeIdleAfterMs + 20);
  assert.deepEqual(tabs.removed, [100]);
});

await check('a check that needs a click is reported once, and given up on in time', async () => {
  directly(challenge);
  tabs.add(() => challengeAnswer);
  let told = 0;
  tabRoute.onCheckWaiting = () => { told++; };
  const response = await eksiFetch(URL_);
  assert.equal(response.status, 403);
  assert.equal(response.headers.get('cf-mitigated'), 'challenge');
  assert.equal(told, 1);
});

await check('the account checker never opens a tab, and does not wait', async () => {
  directly(challenge);
  const response = await eksiFetch(URL_, {}, { openTab: false });
  assert.equal(response.status, 403);
  assert.deepEqual(tabs.created, []);
});

// --- answerEksiFetch in script.js -----------------------------------------------

function declaration(file, opening) {
  const text = fs.readFileSync(path.join(APP_DIR, 'assets/js', file), 'utf8');
  const start = text.indexOf(opening);
  assert.ok(start >= 0, `${file}: ${opening}`);
  const end = text.indexOf('\n}', start);
  return text.slice(start, end + 2);
}

function contentScript() {
  const requests = [];
  const scope = vm.createContext({
    URL, Object, String,
    chrome: { runtime: { id: 'ext', onMessage: { addListener() {} } } },
    location: { origin: 'https://eksisozluk.com' },
    fetch: async (url, init) => {
      requests.push({ url, init });
      return new Response('<html>profile</html>', { status: 200, headers: { 'content-type': 'text/html', 'set-cookie': 'x' } });
    },
  });
  vm.runInContext(`${declaration('script.js', 'function answerEksiFetch')}\nthis.answer = answerEksiFetch;`, scope);
  const ask = (request, sender = { id: 'ext' }) => new Promise(resolve => {
    const kept = scope.answer(request, sender, resolve);
    if (!kept) setTimeout(() => resolve(undefined), 5);
  });
  return { requests, ask };
}

await check('the content script makes the request from the page and answers with what callers read', async () => {
  const { requests, ask } = contentScript();
  const answer = await ask({ action: 'eksiFetch', url: URL_, method: 'GET', headers: { 'x-requested-with': 'XMLHttpRequest' }, body: null });
  // Made in the script's own realm: compared as plain data.
  assert.deepEqual(JSON.parse(JSON.stringify(requests)), [{ url: URL_, init: {
    method: 'GET', headers: { 'x-requested-with': 'XMLHttpRequest' }, credentials: 'same-origin',
  } }]);
  assert.deepEqual(JSON.parse(JSON.stringify(answer)), {
    status: 200, headers: { 'content-type': 'text/html' }, text: '<html>profile</html>',
  });
});

await check('the content script refuses another origin and another sender, and makes no request', async () => {
  const { requests, ask } = contentScript();
  assert.deepEqual(
    JSON.parse(JSON.stringify(await ask({ action: 'eksiFetch', url: 'https://example.com/', method: 'GET', headers: {} }))),
    { error: "not this page's origin" },
  );
  assert.equal(await ask({ action: 'eksiFetch', url: URL_, method: 'GET', headers: {} }, { id: 'someone-else' }), undefined);
  assert.equal(requests.length, 0);
});

console.log(`${tests} eksi fetch checks passed`);
