import {config} from './config.js';
import {log} from './log.js';

/*
 * The extension's way to Ekşi Sözlük.
 *
 * Requests go straight from the background until Ekşi's Cloudflare answers one
 * with a challenge. A background request cannot solve it, but a tab in the same
 * browser already has, so from then on -- for tabRoute.stickyMs -- requests go
 * through the content script of an open Ekşi tab, which makes them from the page
 * (answerEksiFetch in script.js) where they pass like the page's own. With no Ekşi
 * tab open, one is opened in the background and closed again once nothing has
 * gone through it for a while.
 *
 * See openspec/changes/eksi-requests-through-tab.
 */

export const tabRoute = {
  // How long a challenge keeps requests on the tab route before the direct one is tried again.
  stickyMs: 10 * 60 * 1000,
  // Between attempts while a tab loads or is still solving the check.
  retryMs: 2000,
  // A check that has not cleared by then is waiting for a click.
  clickAfterMs: 30 * 1000,
  // How long a request waits for that click before giving up.
  giveUpAfterMs: 5 * 60 * 1000,
  // A tab opened here that nothing has gone through for this long is closed.
  // Longer than the 65 s a rate-limited run waits between requests.
  closeIdleAfterMs: 3 * 60 * 1000,
  // Set by background.js: asks the user to complete the check in the tab.
  onCheckWaiting: null,
};

let tabRouteUntil = 0;
let openedTabId = null;
let idleTimer = null;

// The challenge answers 403 (or 503) with cf-mitigated: challenge. The body is
// the fallback in case a browser hides the header from the extension.
export async function isCloudflareChallenge(response)
{
  if (response.status !== 403 && response.status !== 503) return false;
  if (response.headers.get("cf-mitigated") === "challenge") return true;
  try {
    return (await response.clone().text()).includes("/cdn-cgi/challenge-platform/");
  } catch (err) {
    return false;
  }
}

/** Back to the direct route on the next request. */
export function resetRoute()
{
  tabRouteUntil = 0;
}

/**
 * fetch() for Ekşi Sözlük. Always a Response: when neither route gets past the
 * challenge, the challenge answer itself, so callers see a 403 as before.
 * With openTab false (the account checker) no tab is opened and nothing waits.
 */
export async function eksiFetch(url, init = {}, { openTab = true } = {})
{
  let challenged = null;
  if (Date.now() >= tabRouteUntil) {
    const response = await fetch(url, init);
    if (!(await isCloudflareChallenge(response))) return response;
    challenged = response;
    tabRouteUntil = Date.now() + tabRoute.stickyMs;
    log.info("eksiFetch", "Ekşi challenged a background request; going through an Ekşi tab");
  }
  return (await throughTab(url, init, openTab)) || challenged || challengeAnswer();
}

function challengeAnswer()
{
  return new Response("", { status: 403, headers: { "cf-mitigated": "challenge" } });
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const WAIT = "wait";

/** A tab to send to, WAIT while one loads (or was just opened), or null for none. */
async function pickTab(failed, mayOpen)
{
  if (!chrome.tabs?.query) return null;
  const tabs = await chrome.tabs.query({ url: `${config.EksiSozlukURL}/*` });
  const ready = tabs.filter(t => t.status === "complete" && !t.discarded && !failed.has(t.id));
  if (ready.length) return (ready.find(t => t.id === openedTabId) || ready[0]).id;
  if (tabs.length) return WAIT;
  if (!mayOpen) return null;
  const tab = await chrome.tabs.create({ url: `${config.EksiSozlukURL}/`, active: false });
  openedTabId = tab.id;
  log.info("eksiFetch", "opened a background Ekşi tab");
  return WAIT;
}

function toResponse(answer)
{
  // The page's own request failed before Ekşi answered, as a direct fetch would throw.
  if (answer.error) throw new TypeError(`through the Ekşi tab: ${answer.error}`);
  const noBody = [101, 204, 205, 304].includes(answer.status);
  return new Response(noBody ? null : (answer.text ?? ""), { status: answer.status, headers: answer.headers || {} });
}

async function throughTab(url, init, openTab)
{
  // A signal cannot cross to the tab; everything the request is made of can.
  const request = {
    action: "eksiFetch",
    url,
    method: init.method || "GET",
    headers: init.headers || {},
    body: init.body ?? null,
  };
  const patience = openTab ? tabRoute.giveUpAfterMs : 0;
  const started = Date.now();
  const failed = new Set();
  let mayOpen = openTab;
  let told = false;
  for (;;) {
    const tabId = await pickTab(failed, mayOpen);
    if (tabId === null) return null;
    if (tabId === WAIT) {
      // Opened one already, or one is loading: never open a second.
      mayOpen = false;
    } else {
      let answer = null;
      try {
        answer = await chrome.tabs.sendMessage(tabId, request);
      } catch (err) {
        // Closed, navigating, or its content script is not there yet.
      }
      if (!answer) {
        failed.add(tabId);
        continue;
      }
      const response = toResponse(answer);
      if (!(await isCloudflareChallenge(response))) {
        if (tabId === openedTabId) closeWhenIdle();
        return response;
      }
      // That tab is still solving the check, or it waits for a click.
    }
    if (Date.now() - started >= patience) return null;
    if (!told && Date.now() - started >= tabRoute.clickAfterMs) {
      told = true;
      tabRoute.onCheckWaiting?.();
    }
    failed.clear();
    await sleep(tabRoute.retryMs);
  }
}

function closeWhenIdle()
{
  clearTimeout(idleTimer);
  idleTimer = setTimeout(async () => {
    const tabId = openedTabId;
    openedTabId = null;
    idleTimer = null;
    try {
      await chrome.tabs.remove(tabId);
    } catch (err) {
      // Already closed.
    }
  }, tabRoute.closeIdleAfterMs);
}
