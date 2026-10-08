// Offline behavioral regressions. Evaluate the actual runtime declarations with
// browser/HTTP boundaries replaced; no live account or dependencies are needed.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as enums from '../assets/js/enums.js';

const js = new URL('../assets/js/', import.meta.url);
const source = file => fs.readFileSync(new URL(file, js), 'utf8');
function declaration(file, opening) {
  const text = source(file);
  const start = text.indexOf(opening);
  assert.ok(start >= 0, `${file}: ${opening}`);
  const end = text.indexOf('\n}', start);
  assert.ok(end > start);
  return text.slice(start, end + 2);
}
const quiet = { info() {}, warn() {}, err() {}, debug() {}, resetData() {}, constructor: { Levels: { DISABLED: 0 } } };
const config = { EksiSozlukURL: 'https://fixture.invalid', sendData: false };
const utils = { sleep: async () => {}, getUserList: async () => ['u1', 'u2'], cleanUserList() {} };
let tests = 0;
async function check(name, fn) {
  await fn();
  tests++;
  console.log(`ok: ${name}`);
}
function relation(fetch) {
  const scope = vm.createContext({ enums, config, log: quiet, fetch });
  vm.runInContext(`${declaration('relationHandler.js', 'class RelationHandler')}\nthis.handler = new RelationHandler();`, scope);
  return scope.handler;
}
function response(body, status = 200, retryAfter = null) {
  return new Response(body, { status, headers: retryAfter ? { 'Retry-After': retryAfter } : {} });
}
const flags = { m: [true, false, false, false], i: [false, true, false, false], u: [false, false, true, false], b: [false, false, false, true] };
for (const [code, target] of Object.entries(flags)) {
  await check(`${code}: confirmed, rejected and malformed outcomes`, async () => {
    for (const [mode, accepted] of [[enums.BanMode.BAN, ['0', '2']], [enums.BanMode.UNDOBAN, ['{"result":true}']]]) {
      for (const body of [...accepted, '99', '4', 'null', '{"result":false}', 'garbage', '<html>denied</html>',
        '"0"', '"2"', '{"result":"true"}', '{"other":{"result":true}}', '{"result":true} trailing', '{"result":true']) {
        const handler = relation(async url => {
          assert.ok(url.endsWith(`?r=${code}`));
          return response(body);
        });
        const out = await handler.performAction(mode, 1, ...target);
        assert.equal(out.resultType, accepted.includes(body) ? enums.ResultType.SUCCESS : enums.ResultType.FAIL);
        assert.equal(out.successfulAction, accepted.includes(body) ? 1 : 0);
        assert.equal(out.performedAction, 1);
        assert.equal(out.retryAfter, undefined);
      }
      for (const status of [400, 403, 404, 500]) {
        const out = await relation(async () => response('denied', status)).performAction(mode, 1, ...target);
        assert.equal(out.resultType, enums.ResultType.FAIL);
        assert.equal(out.retryAfter, undefined);
      }
    }
  });
}
await check('invalid IDs never count successful or reach HTTP', async () => {
  const handler = relation(() => { throw new Error('must not fetch'); });
  for (const id of [0, '0', -1, null, undefined, 'abc', 1.2]) {
    assert.equal((await handler.performAction(enums.BanMode.BAN, id, ...flags.m)).resultType, enums.ResultType.FAIL);
  }
  assert.equal(handler.successfulAction, 0);
});
await check('rate limit is retryable and is not counted as performed', async () => {
  for (const [header, wait] of [['30', 31], [null, 65]]) {
    const handler = relation(async () => response('', 429, header));
    const out = await handler.performAction(enums.BanMode.BAN, 1, ...flags.m);
    assert.equal(out.resultType, enums.ResultType.FAIL);
    assert.equal(out.retryAfter, wait);
    assert.equal(out.performedAction, 0);
  }
});
await check('failure is current-target state after previous successes', async () => {
  let count = 0;
  const handler = relation(async () => response(++count === 1 ? '0' : '99'));
  await handler.performAction(enums.BanMode.BAN, 1, ...flags.m);
  const out = await handler.performAction(enums.BanMode.BAN, 2, ...flags.m);
  assert.equal(out.resultType, enums.ResultType.FAIL);
  assert.equal(out.successfulAction, 1);
  assert.equal(out.performedAction, 2);
});
await check('screenshot label places source username in parentheses', async () => {
  const scope = vm.createContext({ enums });
  for (const opening of ['function categoryOfNamedAction', 'function getTaskCategory',
    'function taskCategoryOf', 'function describeTarget', 'const CATEGORY_VERBS', 'function generateUnifiedDescription']) {
    vm.runInContext(declaration('queue.js', opening), scope);
  }
  const label = scope.generateUnifiedDescription(enums.BanSource.FOLLOW, {
    banMode: enums.BanMode.BAN, targetTypes: [enums.TargetType.MUTE], sourceAuthor: 'unexpected-error',
  });
  assert.ok(label.includes('(unexpected-error)'));
  assert.ok(label.includes('Sessize Al'));
});
function conversionHarness({ fetch, profile = async () => { throw new Error('profile unavailable'); }, pauseAt = 0 }) {
  const progress = [], finished = [], cachedRemovals = [], checkpoints = [], states = [];
  const storage = {
    saveLastOperationResult: async state => states.push(state),
    getMutedUserList: async () => ['u1', 'u2', 'u3'],
    removeMutedUsers: async names => cachedRemovals.push(...names),
    getBlockedUserList: async () => ['u1', 'u2', 'u3'],
    saveBlockedUserList: async names => cachedRemovals.push(...['u1', 'u2', 'u3'].filter(n => !names.includes(n))),
    saveBlockedUserCount: async () => {},
  };
  let current;
  const registry = {
    registerOperation() { current = { state: 'RUNNING' }; },
    getCurrentOperation() { return current; },
    isPauseRequested() { return false; },
    async completeOperation() { current = null; },
    async checkpointReached(data) {
      checkpoints.push(data);
      if (pauseAt && data.processedCount === pauseAt) {
        current.state = 'PAUSED';
        return { paused: true, shouldContinue: false };
      }
      return { shouldContinue: true };
    },
  };
  const notification = {
    notify() {}, notifyCooldown() {}, notifyUpdateCounts() {},
    notifyOngoing: (...data) => progress.push(data),
    finishSuccess: (...data) => finished.push(['success', ...data]),
    finishError: (...data) => finished.push(['error', ...data]),
    finishErrorEarlyStop: (...data) => finished.push(['stopped', ...data]),
  };
  const handler = relation(fetch);
  const scope = vm.createContext({ enums, config, utils, log: quiet, fetch,
    relationHandler: handler, storageHandler: storage, notificationHandler: notification,
    resumableOperationRegistry: registry, OperationState: { PAUSED: 'PAUSED', STOPPING: 'STOPPING' },
    processQueue: { currentItemMetadata: {} }, parseHTML() { throw new Error('must not parse profile'); } });
  vm.runInContext(`${declaration('scrapingHandler.js', 'class ScrapingHandler')}\nthis.scrapingHandler = new ScrapingHandler();`, scope);
  scope.scrapingHandler.scrapeAuthorIdFromAuthorProfilePage = profile;
  vm.runInContext(`${declaration('programController.js', 'async function checkPauseOrStop')}\n${declaration('programController.js', 'class ProgramController')}\nthis.programController = new ProgramController();`, scope);
  return { controller: scope.programController, progress, finished, cachedRemovals, checkpoints, states, scope };
}
function page(ids, isLast) {
  return new Response(JSON.stringify({ Relations: { IsLast: isLast, Items: ids.map(id => ({ Id: id, Nick: { Value: `u${id}` } })) } }),
    { headers: { 'Content-Type': 'application/json' } });
}
for (const fromMuted of [true, false]) {
  await check(`${fromMuted ? 'muted to blocked' : 'blocked to muted'}: snapshot, known IDs, continuation and safe order`, async () => {
    const calls = [];
    let reads = 0;
    const h = conversionHarness({ fetch: async (url, options) => {
      calls.push(url);
      if (options.method === 'GET') {
        reads++;
        return reads === 1 ? page([1, 2], false) : page([3], true);
      }
      assert.equal(reads, 2, 'must collect all pages before first mutation');
      return response(url.includes('/addrelation/1?') ? '99' : url.includes('/removerelation/') ? '{"result":true}' : '0');
    } });
    await (fromMuted ? h.controller.blockMutedUsers() : h.controller.migrateBlockedToMuted());
    const replacement = fromMuted ? 'm' : 'u', original = fromMuted ? 'u' : 'm';
    assert.deepEqual(calls.filter(url => url.includes('/userrelation/')).map(url => new URL(url).pathname + new URL(url).search), [
      `/userrelation/addrelation/1?r=${replacement}`,
      `/userrelation/addrelation/2?r=${replacement}`, `/userrelation/removerelation/2?r=${original}`,
      `/userrelation/addrelation/3?r=${replacement}`, `/userrelation/removerelation/3?r=${original}`,
    ]);
    assert.deepEqual(h.cachedRemovals, ['u2', 'u3']);
    assert.deepEqual(h.finished[0].slice(3, 6), [2, 3, 3]);
    assert.equal(h.progress.at(-1)[1], 3);
  });
}
await check('profile failure affects only one target', async () => {
  const h = conversionHarness({ fetch: async (url, options) => options.method === 'GET'
    ? new Response(JSON.stringify({ Relations: { IsLast: true, Items: [{ Id: 0, Nick: { Value: 'missing' } }, { Id: 2, Nick: { Value: 'u2' } }] } }), { headers: { 'Content-Type': 'application/json' } })
    : response(url.includes('/addrelation/') ? '0' : '{"result":true}') });
  await h.controller.blockMutedUsers();
  assert.deepEqual(h.finished[0].slice(3, 6), [1, 2, 2]);
});
await check('failed source removal stays in cache and is not successful', async () => {
  const h = conversionHarness({ fetch: async (url, options) => options.method === 'GET' ? page([1], true)
    : response(url.includes('/addrelation/') ? '0' : '{"result":false}') });
  await h.controller.blockMutedUsers();
  assert.deepEqual(h.cachedRemovals, []);
  assert.deepEqual(h.finished[0].slice(3, 6), [0, 1, 1]);
});
await check('pause checkpoint prevents later actions and preserves completed removals', async () => {
  let posts = 0;
  const h = conversionHarness({ pauseAt: 1, fetch: async (url, options) => {
    if (options.method === 'GET') return page([1, 2], true);
    posts++;
    return response(url.includes('/addrelation/') ? '0' : '{"result":true}');
  } });
  await h.controller.blockMutedUsers();
  assert.equal(posts, 2);
  assert.equal(h.states.at(-1), 'PAUSED');
  assert.equal(h.finished.length, 0);
  assert.deepEqual(h.cachedRemovals, ['u1']);
});
await check('collection failure finishes as an error rather than success', async () => {
  const h = conversionHarness({ fetch: async () => response('denied', 500) });
  await h.controller.blockMutedUsers();
  assert.equal(h.finished[0][0], 'error');
  assert.equal(h.states.at(-1), 'FAILED');
});
await check('date-based follow refuses a failed preparation', async () => {
  let calls = 0;
  const h = conversionHarness({ fetch: async () => { calls++; return response('{"result":false}'); } });
  const out = await h.controller._followAfterClearing(1, 'u1', 'BLOCKED_USERS', null);
  assert.equal(out.resultType, enums.ResultType.FAIL);
  assert.equal(calls, 1);
});
await check('unmute all keeps failed users in the cached list', async () => {
  const h = conversionHarness({ profile: async name => Number(name.slice(1)),
    fetch: async url => response(url.includes('/removerelation/1?') ? '{"result":false}' : '{"result":true}') });
  await h.controller.startUnmuteAll();
  assert.deepEqual(h.cachedRemovals, ['u2', 'u3']);
  assert.deepEqual(h.finished[0].slice(3, 6), [2, 3, 3]);
});
await check('unmute all reports unexpected exceptions and preserves progress', async () => {
  for (const failAt of [1, 2]) {
    const h = conversionHarness({
      profile: async name => {
        const id = Number(name.slice(1));
        if (id === failAt) throw new Error('profile unavailable');
        return id;
      },
      fetch: async () => response('{"result":true}'),
    });
    await h.controller.startUnmuteAll();
    assert.equal(h.finished.length, 1);
    assert.equal(h.finished[0][0], 'error');
    assert.deepEqual(h.finished[0].slice(4, 7), [failAt - 1, failAt, 3]);
    assert.equal(h.states.at(-1), 'FAILED');
    assert.equal(h.controller.isUnmuteAllInProgress, false);
  }
});
await check('unmute all does not report success when setup or cache cleanup fails', async () => {
  for (const method of ['saveLastOperationResult', 'getMutedUserList', 'removeMutedUsers']) {
    const h = conversionHarness({ profile: async name => Number(name.slice(1)),
      fetch: async () => response('{"result":true}') });
    const original = h.scope.storageHandler[method];
    h.scope.storageHandler[method] = async (...args) => {
      if (method !== 'saveLastOperationResult' || args[0] === 'RUNNING') throw new Error('storage unavailable');
      return original(...args);
    };
    await h.controller.startUnmuteAll();
    assert.equal(h.finished.length, 1);
    assert.equal(h.finished[0][0], 'error');
    assert.equal(h.states.at(-1), 'FAILED');
    assert.equal(h.controller.isUnmuteAllInProgress, false);
  }
});
function backgroundHarness(fetch, extra = {}) {
  const handler = relation(fetch), sleeps = [], finished = [];
  const scope = vm.createContext({ enums, config, log: quiet, relationHandler: handler,
    chrome: { runtime: { getManifest: () => ({ version: 'test' }) } },
    utils, programController: { earlyStop: false }, processQueue: { currentItemMetadata: {}, itemAttributes: [] },
    setTimeout: fn => { sleeps.push(true); fn(); },
    ensureNotificationTabExistsAndIsReady: async () => true, handleConfig: async () => {}, handleEksiSozlukURL: async () => true,
    scrapingHandler: { scrapeUserAgent: async () => 'test', scrapeClientNameAndId: async () => ({ clientName: 'me', clientId: 10 }),
      scrapeAuthorIdFromAuthorProfilePage: async name => Number(name.slice(1)),
      scrapeFollowRestrictions: async () => ({ isBlocked: () => true, isMuted: () => false }),
      ...extra.scrapingHandler },
    storageHandler: extra.storageHandler,
    notificationHandler: new Proxy({ finishSuccess: (...args) => finished.push(args) }, { get: (o, k) => o[k] || (() => {}) }),
    createEksiSozlukUser: () => ({}), createEksiSozlukTitle: () => ({}), createEksiSozlukEntry: () => ({}),
    Action: class { constructor(data) { Object.assign(this, data); } }, ActionConfig: class {},
  });
  scope.programController._performActionWithRetry = (...args) => handler.performAction(...args);
  vm.runInContext(declaration('background.js', 'async function processHandler'), scope);
  return { scope, handler, sleeps, finished };
}
// Exercise the ordinary single/list/audience action dispatcher, including the
// accumulated-success bug and permanent failures that used to sleep 62 seconds.
for (const explicit of [null, 'ENGEL_KALDIR_VE_TAKIP_ET', 'SESSIZDEN_CIKAR_VE_TAKIP_ET']) {
  await check(`background follow preparation: ${explicit || 'TAKIP_ET'}`, async () => {
    const calls = [];
    const { scope, sleeps, finished } = backgroundHarness(async url => {
      calls.push(new URL(url).pathname + new URL(url).search);
      return response(url.includes('/removerelation/2?') ? '{"result":false}' : url.includes('/removerelation/') ? '{"result":true}' : '0');
    });
    await scope.processHandler(enums.BanSource.LIST, enums.BanMode.BAN, null, null, null, enums.TargetType.FOLLOW, null, null, null, null, explicit || 'TAKIP_ET');
    assert.equal(sleeps.length, 0, 'a permanent failure must not trigger cooldown');
    assert.equal(calls.length, 3);
    assert.ok(!calls.some(url => url.includes('/addrelation/2?')));
    assert.deepEqual(finished[0].slice(2, 5), [1, 2, 2]);
  });
}
await check('bounded background rate-limit retry honors delay and counts exhausted target', async () => {
  let calls = 0;
  const { scope, sleeps, finished } = backgroundHarness(async () => { calls++; return response('', 429, '2'); });
  await scope.processHandler(enums.BanSource.SINGLE, enums.BanMode.BAN, null, 'u1', 1, enums.TargetType.USER);
  assert.equal(calls, 2);
  assert.equal(sleeps.length, 3);
  assert.deepEqual(finished[0].slice(2, 5), [0, 1, 1]);
});
await check('combined bulk removal retains failed users in both cached lists', async () => {
  let blockedCache = ['u1', 'u2'], mutedRemoved = [];
  const { scope, finished } = backgroundHarness(async url => response(
    /removerelation\/(1|3)\?/.test(url) ? '{"result":false}' : '{"result":true}'), {
    scrapingHandler: {
      scrapeAllBlockedUsers: async () => ({ success: true, usernames: ['u1', 'u2'] }),
      scrapeAllMutedUsers: async () => ({ success: true, usernames: ['u3', 'u4'] }),
    },
    storageHandler: {
      getBlockedUserList: async () => blockedCache,
      saveBlockedUserList: async users => { blockedCache = Array.from(users); },
      saveBlockedUserCount: async count => assert.equal(count, 1),
      removeMutedUsers: async users => { mutedRemoved = Array.from(users); },
    },
  });
  await scope.processHandler(enums.BanSource.UNDOBANALL, enums.BanMode.UNDOBAN);
  assert.deepEqual(blockedCache, ['u1']);
  assert.deepEqual(mutedRemoved, ['u4']);
  assert.deepEqual(finished[0].slice(2, 5), [2, 4, 4]);
});
console.log(`${tests} relation action regression checks passed`);
