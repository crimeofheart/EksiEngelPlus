// Offline checks for the history sync client. chrome.storage and the server are
// replaced with in-memory fakes; nothing here needs a network or a browser.
import assert from 'node:assert/strict';

function store(initial = {}) {
  const data = structuredClone(initial);
  const pick = keys => {
    if (keys == null) return structuredClone(data);
    const list = typeof keys === 'string' ? [keys] : Array.isArray(keys) ? keys : Object.keys(keys);
    return Object.fromEntries(list.filter(k => k in data).map(k => [k, structuredClone(data[k])]));
  };
  return {
    data,
    get(keys, cb) { const out = pick(keys); if (cb) { cb(out); return undefined; } return Promise.resolve(out); },
    set(items, cb) { Object.assign(data, structuredClone(items)); if (cb) { cb(); return undefined; } return Promise.resolve(); },
    remove(keys, cb) { for (const k of [].concat(keys)) delete data[k]; if (cb) { cb(); return undefined; } return Promise.resolve(); },
  };
}

const local = store({ clientIdentity: { client_name: 'me', client_uid: 42 }, config: { syncHistory: true } });
const sync = store();
globalThis.chrome = {
  storage: { local, sync },
  runtime: { getManifest: () => ({ version: '0.6.0-test' }), lastError: null },
};

/** A scripted server: routes by method + path, records every request. */
class FakeServer {
  constructor() { this.requests = []; this.routes = {}; this.down = false; }
  on(method, path, handler) { this.routes[`${method} ${path}`] = handler; return this; }
  fetch = async (url, init) => {
    const path = new URL(url).pathname.replace('/api/sync/', '');
    const key = `${init.method} ${path.split('?')[0]}`;
    const body = init.body ? JSON.parse(init.body) : null;
    this.requests.push({ key, path, body, headers: init.headers });
    if (this.down) throw new TypeError('network down');
    const handler = this.routes[key];
    if (!handler) return new Response('{}', { status: 404 });
    const [status, json] = await handler(body, path);
    // A 204 may carry no body at all, not even an empty string.
    return new Response(json === undefined ? null : JSON.stringify(json), { status });
  };
  count(key) { return this.requests.filter(r => r.key === key).length; }
  last(key) { return this.requests.filter(r => r.key === key).at(-1); }
}

const { config } = await import('../assets/js/config.js');
const enums = await import('../assets/js/enums.js');
const { HistorySync, mergeStatus, eventsForOutcome, appendCapped, OUTBOX_CAP, CHECK_INTERVAL_MS } =
  await import('../assets/js/historySync.js');
config.serverURL = 'https://fixture.invalid/api/action/';
config.logConsole = false;
const { log } = await import('../assets/js/log.js');
log.logConsole = false;

let tests = 0;
async function check(name, fn) {
  await fn();
  tests++;
  console.log(`ok: ${name}`);
}

function fresh(server) {
  for (const key of Object.keys(local.data)) {
    if (key !== 'clientIdentity') delete local.data[key];
  }
  local.data.config = { syncHistory: true };
  for (const key of Object.keys(sync.data)) delete sync.data[key];
  globalThis.fetch = server.fetch;
  return new HistorySync();
}

function acceptingServer() {
  const server = new FakeServer();
  let cursor = 0;
  server
    .on('POST', 'enroll/', body => [201, { device_token: 'tok-1', join_key: body.join_key ? null : 'join-1' }])
    .on('POST', 'push/', body => [200, {
      tasks: body.tasks.map(t => ({ id: t.id, result: 'ok', epoch: t.epoch, status: t.status, cancel_requested: false })),
      events_accepted: body.events.map(e => e.id),
      cursor: ++cursor,
    }])
    .on('GET', 'pull/', () => [200, { tasks: [], next_cursor: cursor, has_more: false }]);
  return server;
}

const outcome = { banMode: enums.BanMode.BAN, id: 7, statuses: { [enums.TargetType.USER]: 'SUCCESS' } };

await check('status only moves forward, and a terminal one never changes', async () => {
  assert.equal(mergeStatus(null, 'QUEUED'), 'QUEUED');
  assert.equal(mergeStatus('QUEUED', 'RUNNING'), 'RUNNING');
  assert.equal(mergeStatus('RUNNING', 'QUEUED'), 'RUNNING');
  assert.equal(mergeStatus('DONE', 'FAILED'), 'DONE');
  assert.equal(mergeStatus('CANCELLED', 'DONE'), 'CANCELLED');
});

await check('one event per relation attempted, with the wire names the server expects', async () => {
  let n = 0;
  const events = eventsForOutcome(
    { banMode: enums.BanMode.UNDOBAN, id: '9',
      statuses: { [enums.TargetType.USER]: 'SUCCESS', [enums.TargetType.MUTE]: 'FAIL' } },
    { taskId: 't', actor: { id: 42, nick: 'me' }, now: 1000, uuid: () => `u${n++}` },
  );
  assert.deepEqual(events.map(e => [e.kind, e.direction, e.result]),
    [['BLOCK', 'REMOVE', 'OK'], ['MUTE', 'REMOVE', 'FAIL']]);
  assert.equal(events[0].target_eksi_id, 9);
  assert.equal(events[0].task_id, 't');
  assert.deepEqual(eventsForOutcome({ ...outcome, id: 0 }, { actor: { id: 1 }, now: 1, uuid: () => 'x' }), []);
  assert.deepEqual(eventsForOutcome(outcome, { actor: null, now: 1, uuid: () => 'x' }), []);
});

await check('the outbox drops its oldest past the cap and says how many', async () => {
  const list = Array.from({ length: OUTBOX_CAP - 1 }, (_, i) => i);
  assert.equal(appendCapped(list, [1, 2, 3]), 2);
  assert.equal(list.length, OUTBOX_CAP);
  assert.equal(list[0], 2);
});

await check('off means nothing is written, not even to the outbox', async () => {
  const server = acceptingServer();
  const h = fresh(server);
  local.data.config = { syncHistory: false };
  await h.recordOutcome(outcome);
  await h.trackTask({ id: 'a', banSource: '1', banMode: '1' }, 'QUEUED');
  await h.flush();
  assert.equal(server.requests.length, 0);
  assert.equal((await h.status()).pendingEvents, 0);
});

await check('first flush enrols, keeps the join key in synced storage, and empties the outbox', async () => {
  const server = acceptingServer();
  const h = fresh(server);
  await h.trackTask({ id: 'task-a', banSource: '4', banMode: '1', description: 'Listeden Engelle', params: {} }, 'QUEUED');
  h.setCurrentTask('task-a');
  await h.recordOutcome(outcome);
  await h.flush();
  assert.equal(server.count('POST enroll/'), 1);
  assert.equal(sync.data.historyJoinKey, 'join-1');
  const push = server.last('POST push/');
  assert.equal(push.headers.Authorization, 'Device tok-1');
  assert.equal(push.body.events.length, 1);
  assert.equal(push.body.events[0].actor_eksi_id, 42);
  assert.equal(push.body.events[0].task_id, 'task-a');
  assert.equal(push.body.tasks[0].ban_source, 4);
  assert.equal((await h.status()).pendingEvents, 0);
});

await check('a second install on the same synced profile enrols into the same account', async () => {
  const server = acceptingServer();
  const h = fresh(server);
  sync.data.historyJoinKey = 'join-existing';
  await h.flush();
  assert.equal(server.last('POST enroll/').body.join_key, 'join-existing');
  assert.equal(sync.data.historyJoinKey, 'join-existing');
});

await check('server down: the run carries on, the outbox keeps everything, sync backs off', async () => {
  const server = acceptingServer();
  const h = fresh(server);
  await h.flush();
  server.down = true;
  for (let i = 0; i < 30; i++) await h.recordOutcome({ ...outcome, id: 100 + i });
  await h.flush({ force: true });
  const status = await h.status();
  assert.equal(status.pendingEvents, 30);
  assert.ok(status.deferredUntil > Date.now());
  const before = server.requests.length;
  await h.flush();
  assert.equal(server.requests.length, before, 'a backed-off flush does not even try');
  server.down = false;
  await h.flush({ force: true });
  assert.equal((await h.status()).pendingEvents, 0);
});

await check('a revoked token means enrolling again, not failing forever', async () => {
  const server = acceptingServer();
  const h = fresh(server);
  await h.flush();
  server.on('POST', 'push/', () => [401, { detail: 'revoked' }]);
  await h.recordOutcome(outcome);
  await h.flush({ force: true });
  assert.equal((await h.status()).enrolled, false);
});

await check('a stale epoch stops the task here and forgets it', async () => {
  const server = acceptingServer();
  const lost = [];
  const h = fresh(server);
  h.onTaskLost = id => lost.push(id);
  server.on('POST', 'push/', body => [200, {
    tasks: body.tasks.map(t => ({ id: t.id, result: 'conflict', epoch: 1, status: 'QUEUED' })),
    events_accepted: [], cursor: 1,
  }]);
  await h.trackTask({ id: 'task-b', banSource: '1', banMode: '1' }, 'RUNNING');
  await h.flush();
  assert.deepEqual(lost, ['task-b']);
  await h.flush({ force: true });
  // Nothing left to say about it: the next flush has nothing to push.
  assert.equal(server.count('POST push/'), 1);
});

await check('pull: other devices\' tasks show as remote rows; own cancels and takeovers act here', async () => {
  const server = acceptingServer();
  const cancelled = [], lost = [];
  let changed = 0;
  const h = fresh(server);
  h.onCancelRequested = id => cancelled.push(id);
  h.onTaskLost = id => lost.push(id);
  h.onRemoteChanged = () => changed++;
  await h.trackTask({ id: 'mine-1', banSource: '1', banMode: '1' }, 'RUNNING');
  await h.trackTask({ id: 'mine-2', banSource: '1', banMode: '1' }, 'QUEUED');
  server.on('GET', 'pull/', () => [200, {
    tasks: [
      { id: 'mine-1', is_mine: true, status: 'RUNNING', cancel_requested: true },
      { id: 'mine-2', is_mine: false, status: 'QUEUED', owner: { label: 'phone', client: 'ANDROID' },
        description: 'Tek Kullanıcı Engelle', params: { retry: { banSource: '1' } }, performed: 0, planned: 1 },
      { id: 'theirs', is_mine: false, status: 'RUNNING', owner: { label: 'desktop', client: 'EXTENSION' },
        description: 'Favori Edenleri Engelle', params: {}, performed: 3, planned: 10 },
      { id: 'finished', is_mine: false, status: 'DONE', owner: null, params: {} },
    ],
    next_cursor: 9, has_more: false,
  }]);
  await h.flush();
  assert.deepEqual(cancelled, ['mine-1']);
  assert.deepEqual(lost, ['mine-2']);
  assert.ok(changed > 0);
  const rows = h.remoteRows();
  assert.deepEqual(rows.map(r => r.taskId).sort(), ['mine-2', 'theirs']);
  const theirs = rows.find(r => r.taskId === 'theirs');
  assert.equal(theirs.ownerLabel, 'desktop');
  assert.equal(theirs.runnableHere, false);
  assert.equal(rows.find(r => r.taskId === 'mine-2').runnableHere, true);
});

await check('progress reaches the server even after the first snapshot was sent', async () => {
  const server = acceptingServer();
  const h = fresh(server);
  await h.trackTask({ id: 'task-p', banSource: '2', banMode: '1', params: {} }, 'QUEUED');
  await h.flush();
  h.setCurrentTask('task-p');
  await h.progress(3, 4, 10);
  await h.flush();
  const task = server.last('POST push/').body.tasks.find(t => t.id === 'task-p');
  assert.deepEqual([task.status, task.performed, task.planned, task.ban_source], ['RUNNING', 4, 10, 2]);
});

await check('erasing the account forgets everything here and turns sync off', async () => {
  const server = acceptingServer().on('DELETE', 'account/', () => [204, undefined]);
  const h = fresh(server);
  await h.flush();
  await h.recordOutcome(outcome);
  await h.deleteAccount();
  assert.equal(server.count('DELETE account/'), 1);
  assert.equal(local.data.config.syncHistory, false);
  assert.equal(sync.data.historyJoinKey, undefined);
  const status = await h.status();
  assert.deepEqual([status.enabled, status.enrolled, status.pendingEvents], [false, false, 0]);
});

await check('pairing waits for approval, then takes the new join key and pulls from the top', async () => {
  let state = 'PENDING';
  const server = acceptingServer()
    .on('POST', 'pair/claim/', () => [202, { request_id: 7, status: 'PENDING' }])
    .on('GET', 'pair/claim/7/', () => [200, { status: state, join_key: state === 'APPROVED' ? 'join-2' : null }]);
  const h = fresh(server);
  await h.flush();
  sync.data.historyJoinKey = 'join-1';
  await h.pairClaim('7k3m-qx9a');
  assert.equal(server.last('POST pair/claim/').body.code, '7k3m-qx9a');
  assert.equal((await h.pairStatus()).status, 'PENDING');
  assert.equal(sync.data.historyJoinKey, 'join-1', 'nothing changes before approval');
  state = 'APPROVED';
  const pullsBefore = server.count('GET pull/');
  assert.equal((await h.pairStatus()).status, 'APPROVED');
  assert.equal(sync.data.historyJoinKey, 'join-2');
  assert.equal(server.last('GET pull/').path, 'pull/');
  assert.ok(server.count('GET pull/') > pullsBefore);
  assert.equal((await h.pairStatus()).status, null, 'the request is forgotten once answered');
});

await check('a refused request changes nothing', async () => {
  const server = acceptingServer()
    .on('POST', 'pair/claim/', () => [202, { request_id: 8, status: 'PENDING' }])
    .on('GET', 'pair/claim/8/', () => [200, { status: 'DENIED', join_key: null }]);
  const h = fresh(server);
  await h.flush();
  sync.data.historyJoinKey = 'join-1';
  await h.pairClaim('AAAA-AAAA');
  assert.equal((await h.pairStatus()).status, 'DENIED');
  assert.equal(sync.data.historyJoinKey, 'join-1');
});

await check('removing a device keeps this key unless a new one comes; removing this one signs out', async () => {
  const server = acceptingServer().on('DELETE', 'devices/4/', () => [200, { join_key: null }])
    .on('DELETE', 'devices/5/', () => [200, { join_key: 'join-3' }])
    .on('DELETE', 'devices/6/', () => [200, { join_key: null }]);
  const h = fresh(server);
  await h.flush();
  const kept = sync.data.historyJoinKey;
  await h.removeDevice(4, false);
  assert.equal(sync.data.historyJoinKey, kept, 'another profile\'s device: this key stands');
  await h.removeDevice(5, false);
  assert.equal(sync.data.historyJoinKey, 'join-3', 'it shared this key, so a new one replaces it');
  await h.removeDevice(6, true);
  assert.equal(sync.data.historyJoinKey, undefined);
  assert.equal((await h.status()).enrolled, false);
});

await check('at the daily cap the rest wait in the outbox and pushing stops', async () => {
  // Closed while recording, so all five are waiting when the capped push comes.
  let open = false;
  const server = acceptingServer().on('POST', 'push/', body => open ? [200, {
    tasks: [], events_accepted: body.events.slice(0, 2).map(e => e.id), capped: true, cursor: 1,
  }] : [503, {}]);
  const h = fresh(server);
  for (let i = 0; i < 5; i++) await h.recordOutcome({ ...outcome, id: 100 + i });
  await h.flush();
  assert.equal((await h.status()).pendingEvents, 5);
  open = true;
  const before = server.count('POST push/');
  await h.flush({ force: true });
  assert.equal(server.count('POST push/') - before, 1, 'one push, not ten');
  assert.equal((await h.status()).pendingEvents, 3);
});

await check('nicks name the events they belong to, and only those', async () => {
  const server = acceptingServer();
  const h = fresh(server);
  await h.rememberNicks(['7'], ['yedi']);                    // resolved before the action
  await h.recordOutcome(outcome);                             // id 7
  await h.recordOutcome({ ...outcome, id: 8 });               // not known yet
  await h.rememberNicks(['8', '900'], ['sekiz', 'dokuzyuz']); // 8 resolved later; 900 never acted on
  await h.flush();
  const push = server.last('POST push/').body;
  assert.deepEqual(push.events.map(e => [e.target_eksi_id, e.target_nick]), [[7, 'yedi'], [8, 'sekiz']]);
  assert.deepEqual(push.nicks, [], 'a nick nobody acted on is never sent');
});

function checkerServer() {
  return acceptingServer()
    .on('GET', 'pull/', () => [200, { tasks: [], next_cursor: 0, has_more: false, checker: true }]);
}

/** Runs fn with the clock moved forward, for the check interval. */
async function later(ms, fn) {
  const realNow = Date.now;
  Date.now = () => realNow() + ms;
  try { await fn(); } finally { Date.now = realNow; }
}

await check('the checker reports what Ekşi answers and stops at the first refusal', async () => {
  const server = checkerServer()
    .on('GET', 'checks/', () => [200, { accounts: [
      { eksi_id: 42, nick: 'ben' },
      { eksi_id: 5, nick: 'iki kelime' },
      { eksi_id: 6, nick: 'perde' },
      { eksi_id: 7, nick: 'sonra' },
    ], delay_ms: 0 }])
    .on('POST', 'checks/', body => [200, { recorded: body.results.length }]);
  const h = fresh(server);
  const pages = {
    '/biri/ben': [200, ''],
    '/son-entryleri?nick=ben': [200, '<li data-author-id="42"></li><li data-author-id="42"></li>'],
    '/biri/iki-kelime': [404, ''],
    '/biri/perde': [403, 'Just a moment...'],
  };
  const asked = [];
  globalThis.fetch = async (url, init) => {
    if (!url.startsWith(config.EksiSozlukURL)) return server.fetch(url, init);
    const path = url.slice(config.EksiSozlukURL.length);
    asked.push(path);
    const [status, text] = pages[path] || [500, ''];
    return new Response(text, { status });
  };
  await h.checkAccounts();
  assert.equal(server.count('GET checks/'), 0, 'not a checker until a pull says so');
  await h.flush();
  await h.checkAccounts();
  assert.deepEqual(asked, ['/biri/ben', '/son-entryleri?nick=ben', '/biri/iki-kelime', '/biri/perde']);
  assert.deepEqual(server.last('POST checks/').body, { results: [
    { eksi_id: 42, nick: 'ben', profile: 200, entries: 200, author_ids: [42] },
    { eksi_id: 5, nick: 'iki kelime', profile: 404 },
  ], refused: 403 });
  await h.checkAccounts();
  assert.equal(server.count('GET checks/'), 1, 'at most one batch per interval');
  await later(CHECK_INTERVAL_MS, () => h.checkAccounts());
  assert.equal(server.count('GET checks/'), 2);
});

await check('a browser the admin unmarked stops asking', async () => {
  const server = checkerServer().on('GET', 'checks/', () => [403, { detail: 'not a checker' }]);
  const h = fresh(server);
  await h.flush();
  await h.checkAccounts();
  await later(CHECK_INTERVAL_MS, () => h.checkAccounts());
  assert.equal(server.count('GET checks/'), 1);
});

console.log(`${tests} history sync checks passed`);
