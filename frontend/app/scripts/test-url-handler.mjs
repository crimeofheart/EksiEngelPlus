// Offline checks for the Ekşi Sözlük access check: a Cloudflare challenge is
// told apart from an Ekşi that cannot be reached. fetch is a fake.
import assert from 'node:assert/strict';

globalThis.chrome = { storage: { local: { get: async () => ({}), set: async () => {} } } };
const { config } = await import('../assets/js/config.js');
const { log } = await import('../assets/js/log.js');
log.logConsole = false;
const { handleEksiSozlukURL, EksiAccess } = await import('../assets/js/urlHandler.js');
const { isCloudflareChallenge, resetRoute } = await import('../assets/js/eksiFetch.js');

const CHALLENGE_PAGE = '<html><title>Just a moment...</title><script src="/cdn-cgi/challenge-platform/h/g/orchestrate/chl/v1"></script></html>';

let tests = 0;
async function check(name, fn) {
  await fn();
  tests++;
  console.log(`ok: ${name}`);
}

/** Answers each URL from the table; anything else is a network failure. */
function serve(table) {
  resetRoute();
  const asked = [];
  globalThis.fetch = async (url) => {
    asked.push(url);
    if (!(url in table)) throw new TypeError('network down');
    const [status, body, headers = {}] = table[url];
    return new Response(body, { status, headers });
  };
  return asked;
}

await check('a challenge is recognised by its header, and by its page when the header is hidden', async () => {
  assert.equal(await isCloudflareChallenge(new Response('', { status: 403, headers: { 'cf-mitigated': 'challenge' } })), true);
  assert.equal(await isCloudflareChallenge(new Response(CHALLENGE_PAGE, { status: 403 })), true);
  assert.equal(await isCloudflareChallenge(new Response('forbidden', { status: 403 })), false);
  assert.equal(await isCloudflareChallenge(new Response(CHALLENGE_PAGE, { status: 200 })), false);
});

await check('a challenge no Ekşi tab gets past is not called unreachable, and no new address is looked up', async () => {
  const asked = serve({ [config.EksiSozlukURL]: [403, CHALLENGE_PAGE, { 'cf-mitigated': 'challenge' }] });
  assert.equal(await handleEksiSozlukURL(), EksiAccess.CHALLENGED);
  assert.deepEqual(asked, [config.EksiSozlukURL]);
});

await check('a reachable Ekşi is ok, and an unreachable one still is unreachable', async () => {
  serve({ [config.EksiSozlukURL]: [200, '<html></html>'] });
  assert.equal(await handleEksiSozlukURL(), EksiAccess.OK);
  serve({});
  assert.equal(await handleEksiSozlukURL(), EksiAccess.UNREACHABLE);
});

console.log(`${tests} url handler checks passed`);
