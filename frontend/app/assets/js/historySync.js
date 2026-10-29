import {log} from './log.js';
import {config, getConfig, saveConfig} from './config.js';
import * as enums from './enums.js';
import {commHandler} from './commHandler.js';

/*
 * History sync: the outbox of per-target outcomes and task states, and the
 * client for /api/sync/.
 *
 * Nothing here may slow down or fail a run. Outcomes are appended to an
 * outbox in chrome.storage.local and sent later; a network error, a timeout or
 * a 5xx leaves them there for the next attempt. The run that produced them
 * never waits on the server and never hears about it.
 *
 * Mirrored by HistorySyncWorker and HistoryOutbox on Android. Wire names (kind,
 * direction, result, status) are the backend's history/constants.py.
 */

const OUTBOX_KEY = "historyOutbox";
const STATE_KEY = "historySync";
// In chrome.storage.sync, so another install on the same synced browser profile
// joins this account without a pairing code -- with its own device token.
const JOIN_KEY = "historyJoinKey";

export const OUTBOX_CAP = 20000;
// Ids whose nicks are remembered for naming events. A run over a 10,000-name
// author list fits; past this the oldest are forgotten first.
const NICK_CACHE = 20000;
const PUSH_EVENTS = 500;
const PUSH_TASKS = 100;
const PUSH_NICKS = 1000;
// While a task runs: push and look for a cancel every 25 outcomes or 60 seconds.
export const FLUSH_EVERY_EVENTS = 25;
export const FLUSH_INTERVAL_MS = 60 * 1000;
export const ALARM_NAME = "historySync";
export const ALARM_PERIOD_MINUTES = 5;
const MAX_BACKOFF_MS = 30 * 60 * 1000;
const REQUEST_TIMEOUT_MS = 10 * 1000;

export const KIND_OF_TARGET = {
  [enums.TargetType.USER]: "BLOCK",
  [enums.TargetType.TITLE]: "TITLE_BLOCK",
  [enums.TargetType.MUTE]: "MUTE",
  [enums.TargetType.FOLLOW]: "FOLLOW",
};

const STATUS_RANK = { QUEUED: 0, RUNNING: 1, DONE: 2, FAILED: 2, CANCELLED: 2, STOPPED: 2 };
const TERMINAL = new Set(["DONE", "FAILED", "CANCELLED", "STOPPED"]);

/** Same rule as the server: forward only, and a terminal status never changes. */
export function mergeStatus(current, incoming) {
  if (!current) return incoming;
  if (TERMINAL.has(current)) return current;
  return STATUS_RANK[incoming] > STATUS_RANK[current] ? incoming : current;
}

/** The events one performAction outcome stands for: one per relation it attempted. */
export function eventsForOutcome({ banMode, id, statuses }, { taskId, actor, now, uuid }) {
  const targetId = Number(id);
  if (!actor || !Number.isSafeInteger(targetId) || targetId <= 0) return [];
  const direction = banMode == enums.BanMode.UNDOBAN ? "REMOVE" : "ADD";
  return Object.entries(statuses).map(([targetType, status]) => ({
    id: uuid(),
    task_id: taskId || null,
    kind: KIND_OF_TARGET[targetType],
    direction,
    result: status === enums.ResultTypeHttpReq.SUCCESS ? "OK" : "FAIL",
    actor_eksi_id: actor.id,
    actor_nick: actor.nick || null,
    target_eksi_id: targetId,
    target_nick: null,
    occurred_at: now,
  })).filter(e => e.kind);
}

/** Appends to an outbox array, dropping the oldest past `cap`. Returns how many were dropped. */
export function appendCapped(list, items, cap = OUTBOX_CAP) {
  list.push(...items);
  const excess = list.length - cap;
  if (excess > 0) {
    list.splice(0, excess);
    return excess;
  }
  return 0;
}

function emptyOutbox() {
  return { events: [], tasks: {}, nicks: {}, gap: 0, rev: 0 };
}

function emptyState() {
  return {
    deviceToken: null,
    cursor: 0,
    // Own tasks: {epoch, status}. Remote tasks: what the last pull said.
    own: {},
    remote: {},
    failures: 0,
    backoffUntil: 0,
    lastFlushAt: 0,
  };
}

function syncBase() {
  if (!config.serverURL) return null;
  try {
    return new URL("/api/sync/", config.serverURL).href;
  } catch (e) {
    return null;
  }
}

function deviceLabel() {
  const ua = (typeof navigator !== "undefined" && navigator.userAgent) || "";
  const browser = /Firefox\//.test(ua) ? "Firefox" : /Edg\//.test(ua) ? "Edge" : "Chrome";
  const platform = /Android/.test(ua) ? "Android" : /Windows/.test(ua) ? "Windows"
    : /Mac OS X/.test(ua) ? "macOS" : /Linux/.test(ua) ? "Linux" : "";
  return platform ? `${browser} · ${platform}` : browser;
}

class SyncError extends Error {
  constructor(status, body) {
    super(`history sync HTTP ${status}`);
    this.status = status;
    this.body = body;
  }
}

export class HistorySync {
  #state = null;
  #outbox = null;
  #loading = null;
  #persistTimer = null;
  #flushing = null;
  #sinceFlush = 0;
  #current = null;
  // id -> nick, most recently resolved last. See rememberNicks.
  #nicks = new Map();

  // Set by background.js, which owns the queue and the running operation.
  onCancelRequested = null;
  onTaskLost = null;
  onRemoteChanged = null;

  async #load() {
    if (this.#state) return;
    if (!this.#loading) {
      this.#loading = (async () => {
        try {
          const stored = await chrome.storage.local.get([STATE_KEY, OUTBOX_KEY]);
          this.#state = { ...emptyState(), ...(stored[STATE_KEY] || {}) };
          this.#outbox = { ...emptyOutbox(), ...(stored[OUTBOX_KEY] || {}) };
        } catch (e) {
          log.warn("history", `could not load sync state: ${e}`);
          this.#state = emptyState();
          this.#outbox = emptyOutbox();
        }
      })();
    }
    await this.#loading;
  }

  /** Written at most once a second; a service-worker kill loses no more than that. */
  #persist() {
    if (this.#persistTimer) return;
    this.#persistTimer = setTimeout(() => this.#persistNow(), 1000);
  }

  async #persistNow() {
    clearTimeout(this.#persistTimer);
    this.#persistTimer = null;
    try {
      await chrome.storage.local.set({ [STATE_KEY]: this.#state, [OUTBOX_KEY]: this.#outbox });
    } catch (e) {
      log.warn("history", `could not save sync state: ${e}`);
    }
  }

  /** The stored setting, not the module copy: the settings page may have changed it. */
  async isEnabled() {
    const stored = await getConfig();
    const value = stored && "syncHistory" in stored ? stored.syncHistory : config.syncHistory;
    return value !== false;
  }

  async #actor() {
    const identity = await commHandler.currentIdentity();
    if (!identity || !identity.client_uid) return null;
    return { id: Number(identity.client_uid), nick: identity.client_name || null };
  }

  // --- what the run reports --------------------------------------------------------

  get currentTaskId() {
    return this.#current;
  }

  /** The queue item now running. Outcomes without one still record, with no task. */
  setCurrentTask(taskId) {
    this.#current = taskId || null;
    this.#sinceFlush = 0;
  }

  clearCurrentTask() {
    this.#current = null;
  }

  /**
   * Records a task's state. `info` carries id, banSource, banMode, description and
   * params; later calls may pass only id plus what changed.
   */
  async trackTask(info, status, counts = null) {
    if (!info || !info.id || !(await this.isEnabled())) return;
    await this.#load();
    const own = this.#state.own[info.id] || { epoch: 0, status: null, base: null, counts: {} };
    if (!own.base) {
      if (info.banSource === undefined) return; // never announced: nothing to update
      const actor = await this.#actor();
      own.base = {
        ban_source: Number(info.banSource) || 0,
        // The display-only modes of migrations and refreshes ("PROCESS",
        // "REFRESH") are not ban modes; they add relations, so BAN stands in.
        ban_mode: Number(info.banMode) === 2 ? 2 : 1,
        description: String(info.description || "").slice(0, 200),
        params: info.params || {},
        created_at: info.createdAt || Date.now(),
        ...(actor ? { actor_eksi_id: actor.id } : {}),
      };
      if (!own.base.ban_source) return;
    }
    const merged = mergeStatus(own.status, status);
    own.status = merged;
    if (counts) own.counts = { ...own.counts, ...counts };
    this.#state.own[info.id] = own;
    this.#outbox.tasks[info.id] = { ...own.base, ...own.counts, id: info.id, epoch: own.epoch, status: merged };
    this.#outbox.rev++;
    this.#persist();
    if (TERMINAL.has(merged)) this.flush({ pull: false });
  }

  /** Progress of the running task, from the counts the progress line shows. */
  async progress(successful, performed, planned) {
    if (!this.#current) return;
    await this.trackTask({ id: this.#current }, "RUNNING", {
      successful: Math.max(0, Number(successful) || 0),
      performed: Math.max(0, Number(performed) || 0),
      planned: Math.max(0, Number(planned) || 0),
    });
  }

  /** RelationHandler.onOutcome: one final outcome for one target. */
  async recordOutcome(outcome) {
    if (!(await this.isEnabled())) return;
    await this.#load();
    const actor = await this.#actor();
    const events = eventsForOutcome(outcome, {
      taskId: this.#current,
      actor,
      now: Date.now(),
      uuid: () => crypto.randomUUID(),
    });
    if (events.length === 0) return;
    for (const event of events) event.target_nick = this.#nicks.get(event.target_eksi_id) || null;
    this.#outbox.gap += appendCapped(this.#outbox.events, events);
    this.#persist();
    this.#sinceFlush += 1;
    const due = this.#sinceFlush >= FLUSH_EVERY_EVENTS
      || Date.now() - (this.#state.lastFlushAt || 0) >= FLUSH_INTERVAL_MS;
    if (due) this.flush({ pull: true });
  }

  /** Nicks for ids the run resolved, so rankings show names rather than numbers. */
  /**
   * Nicks for ids a scrape or a run resolved.
   *
   * Kept in memory and attached only to the events recorded for those ids --
   * the ones already waiting in the outbox included -- never sent on their own.
   * A refresh of the blocked list resolves every nick on it, and none of that
   * is the server's business unless this device acts on someone.
   */
  async rememberNicks(ids, names) {
    if (!(await this.isEnabled())) return;
    await this.#load();
    ids.forEach((id, i) => {
      const eksiId = Number(id);
      if (!Number.isSafeInteger(eksiId) || eksiId <= 0 || !names[i]) return;
      this.#nicks.delete(eksiId);
      this.#nicks.set(eksiId, String(names[i]).slice(0, 96));
    });
    while (this.#nicks.size > NICK_CACHE) this.#nicks.delete(this.#nicks.keys().next().value);
    let named = false;
    for (const event of this.#outbox.events) {
      if (!event.target_nick && this.#nicks.has(event.target_eksi_id)) {
        event.target_nick = this.#nicks.get(event.target_eksi_id);
        named = true;
      }
    }
    if (named) this.#persist();
  }

  // --- the server ------------------------------------------------------------------

  async #call(method, path, body = undefined, { device = true } = {}) {
    const base = syncBase();
    if (!base) throw new SyncError(0, "no server");
    const headers = { "Accept": "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (device) headers["Authorization"] = `Device ${this.#state.deviceToken}`;
    else headers["X-API-Key"] = config.apiKey;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const response = await fetch(base + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
      });
      const text = await response.text();
      const json = text ? JSON.parse(text) : null;
      if (!response.ok) throw new SyncError(response.status, json);
      return json;
    } finally {
      clearTimeout(timer);
    }
  }

  async #ensureDevice() {
    if (this.#state.deviceToken) return;
    let joinKey = null;
    try {
      joinKey = (await chrome.storage.sync.get(JOIN_KEY))[JOIN_KEY] || null;
    } catch (e) {
      // No synced storage here (or it is disabled): enrol a fresh account.
    }
    const body = await this.#call("POST", "enroll/", {
      client: "EXTENSION",
      label: deviceLabel(),
      version: chrome.runtime.getManifest().version,
      join_key: joinKey,
    }, { device: false });
    this.#state.deviceToken = body.device_token;
    this.#state.cursor = 0;
    if (body.join_key) await this.#saveJoinKey(body.join_key);
    await this.#persistNow();
  }

  async #saveJoinKey(joinKey) {
    try {
      if (joinKey) await chrome.storage.sync.set({ [JOIN_KEY]: joinKey });
      else await chrome.storage.sync.remove(JOIN_KEY);
    } catch (e) {
      log.warn("history", `could not save the join key: ${e}`);
    }
  }

  #backOff() {
    this.#state.failures = (this.#state.failures || 0) + 1;
    const delay = Math.min(MAX_BACKOFF_MS, 30 * 1000 * 2 ** (this.#state.failures - 1));
    this.#state.backoffUntil = Date.now() + delay;
  }

  /** Push what is waiting, then (by default) pull. Never throws. */
  flush({ pull = true, force = false } = {}) {
    if (!this.#flushing) {
      this.#flushing = this.#flush({ pull, force }).finally(() => { this.#flushing = null; });
    }
    return this.#flushing;
  }

  async #flush({ pull, force }) {
    try {
      await this.#load();
      if (!(await this.isEnabled())) {
        // Off means nothing leaves the device, and nothing waits to.
        if (this.#outbox.events.length || Object.keys(this.#outbox.tasks).length) {
          this.#outbox = emptyOutbox();
          await this.#persistNow();
        }
        return;
      }
      if (!force && Date.now() < (this.#state.backoffUntil || 0)) return;
      await this.#ensureDevice();
      for (let round = 0; round < 10 && this.#hasPending(); round++) {
        // Stop at the daily cap: what is left waits for tomorrow.
        if ((await this.#pushOnce()) === "capped") break;
      }
      if (pull) await this.#pull();
      this.#state.failures = 0;
      this.#state.backoffUntil = 0;
      this.#state.lastFlushAt = Date.now();
      this.#sinceFlush = 0;
    } catch (e) {
      if (e instanceof SyncError && e.status === 401) {
        // Revoked or erased elsewhere: enrol again next time, starting a new account.
        this.#state.deviceToken = null;
      }
      this.#backOff();
      log.info("history", `sync deferred: ${e.message || e}`);
    } finally {
      if (this.#state) await this.#persistNow();
    }
  }

  #hasPending() {
    const o = this.#outbox;
    return o.events.length > 0 || Object.keys(o.tasks).length > 0
      || Object.keys(o.nicks).length > 0 || o.gap > 0;
  }

  async #pushOnce() {
    const o = this.#outbox;
    const events = o.events.slice(0, PUSH_EVENTS);
    const taskIds = Object.keys(o.tasks).slice(0, PUSH_TASKS);
    const tasks = taskIds.map(id => o.tasks[id]);
    const nickIds = Object.keys(o.nicks).slice(0, PUSH_NICKS);
    const nicks = nickIds.map(id => ({ eksi_id: Number(id), nick: o.nicks[id] }));
    const gap = o.gap;
    const sentRev = o.rev;
    const body = await this.#call("POST", "push/", { tasks, events, nicks, gap });

    const accepted = new Set(body.events_accepted || []);
    o.events = o.events.filter(e => !accepted.has(e.id));
    o.gap = Math.max(0, o.gap - gap);
    for (const id of nickIds) delete o.nicks[id];
    for (const result of body.tasks || []) {
      const sent = tasks.find(t => t.id === result.id);
      // A newer snapshot queued while this one was in flight goes next time.
      if (o.tasks[result.id] === sent || o.rev === sentRev) delete o.tasks[result.id];
      if (result.result === "conflict" || result.result === "not_found") {
        delete this.#state.own[result.id];
        delete o.tasks[result.id];
        this.onTaskLost?.(result.id);
      } else if (TERMINAL.has(result.status) && !o.tasks[result.id]) {
        // Settled on the server and nothing newer queued: no more to say about it.
        delete this.#state.own[result.id];
      } else if (result.cancel_requested) {
        this.onCancelRequested?.(result.id);
      }
    }
    return body.capped ? "capped" : "ok";
  }

  async #pull() {
    let changed = false;
    for (let page = 0; page < 20; page++) {
      const body = await this.#call("GET", `pull/?cursor=${this.#state.cursor || 0}`);
      for (const task of body.tasks || []) {
        changed = this.#absorb(task) || changed;
      }
      this.#state.cursor = body.next_cursor;
      if (!body.has_more) break;
    }
    if (changed) this.onRemoteChanged?.();
  }

  #absorb(task) {
    const own = this.#state.own[task.id];
    if (task.is_mine) {
      delete this.#state.remote[task.id];
      if (own && task.cancel_requested && !TERMINAL.has(task.status)) this.onCancelRequested?.(task.id);
      return false;
    }
    if (own) {
      // Taken over by another device: this one must not run it any further.
      delete this.#state.own[task.id];
      delete this.#outbox.tasks[task.id];
      this.onTaskLost?.(task.id);
    }
    const had = task.id in this.#state.remote;
    if (TERMINAL.has(task.status)) {
      delete this.#state.remote[task.id];
      return had;
    }
    this.#state.remote[task.id] = task;
    return true;
  }

  // --- what the pages ask for ---------------------------------------------------------

  /** Tasks other devices hold that have not finished, as planned-table rows. */
  remoteRows() {
    if (!this.#state) return [];
    return Object.values(this.#state.remote).map(task => ({
      remote: true,
      taskId: task.id,
      ownerLabel: task.owner?.label || (task.owner?.client === "ANDROID" ? "Android" : "başka cihaz"),
      ownerClient: task.owner?.client || null,
      actionDescription: task.description || "İşlem",
      taskStatus: task.status,
      cancelRequested: task.cancel_requested,
      performed: task.performed,
      planned: task.planned,
      runnableHere: !!(task.params && task.params.retry),
      creationDateInStr: (task.created_at || "").slice(11, 16),
    }));
  }

  async cancelRemote(taskId) {
    await this.#load();
    const task = await this.#call("POST", `tasks/${taskId}/cancel/`);
    this.#absorb(task);
    await this.#persistNow();
    this.onRemoteChanged?.();
    return task;
  }

  /** Takes a task over. Returns its retry params, for the caller to enqueue under the same id. */
  async takeOver(taskId) {
    await this.#load();
    const task = await this.#call("POST", `tasks/${taskId}/takeover/`);
    delete this.#state.remote[taskId];
    this.#state.own[taskId] = {
      epoch: task.epoch,
      status: "QUEUED",
      counts: {},
      base: {
        ban_source: task.ban_source,
        ban_mode: task.ban_mode,
        description: task.description,
        params: task.params,
        ...(task.actor_eksi_id ? { actor_eksi_id: task.actor_eksi_id } : {}),
      },
    };
    await this.#persistNow();
    this.onRemoteChanged?.();
    return task;
  }

  epochOf(taskId) {
    return this.#state?.own[taskId]?.epoch || 0;
  }

  async status() {
    await this.#load();
    return {
      enabled: await this.isEnabled(),
      enrolled: !!this.#state.deviceToken,
      pendingEvents: this.#outbox.events.length,
      droppedEvents: this.#outbox.gap,
      lastFlushAt: this.#state.lastFlushAt || null,
      deferredUntil: this.#state.backoffUntil || null,
    };
  }

  async pairStart() {
    await this.#load();
    await this.#ensureDevice();
    return this.#call("POST", "pair/start/");
  }

  /**
   * Asks to join the account whose code was entered. Nothing moves until a
   * device of that account approves; poll pairStatus for the answer.
   */
  async pairClaim(code) {
    await this.#load();
    await this.#ensureDevice();
    const body = await this.#call("POST", "pair/claim/", { code });
    this.#state.pairRequest = body.request_id;
    await this.#persistNow();
    return body;
  }

  /** PENDING, APPROVED, DENIED or EXPIRED; on approval this device has joined. */
  async pairStatus() {
    await this.#load();
    const requestId = this.#state.pairRequest;
    if (!requestId) return { status: null };
    const body = await this.#call("GET", `pair/claim/${requestId}/`);
    if (body.status !== "PENDING") {
      delete this.#state.pairRequest;
      if (body.status === "APPROVED") {
        if (body.join_key) await this.#saveJoinKey(body.join_key);
        // A new account: everything it holds is news to this device.
        this.#state.cursor = 0;
        this.#state.remote = {};
      }
      await this.#persistNow();
      if (body.status === "APPROVED") await this.flush({ force: true });
    }
    return { status: body.status };
  }

  /** Devices asking to join this account. */
  async pairRequests() {
    await this.#load();
    if (!this.#state.deviceToken) return [];
    return this.#call("GET", "pair/requests/");
  }

  async pairDecide(requestId, approve) {
    await this.#load();
    const body = await this.#call("POST", `pair/requests/${requestId}/${approve ? "approve" : "deny"}/`);
    if (approve) await this.flush({ force: true });
    return body;
  }

  async devices() {
    await this.#load();
    if (!this.#state.deviceToken) return [];
    return this.#call("GET", "devices/");
  }

  /**
   * Removes one of the account's devices. The join key rotates, so the new one
   * replaces it in synced storage; removing this device signs it out.
   */
  async removeDevice(deviceId, isCurrent) {
    await this.#load();
    const body = await this.#call("DELETE", `devices/${deviceId}/`);
    if (isCurrent) {
      this.#state = emptyState();
      this.#outbox = emptyOutbox();
      await this.#saveJoinKey(null);
    } else if (body.join_key) {
      // The removed device shared this profile's key, so the server issued a new one.
      await this.#saveJoinKey(body.join_key);
    }
    await this.#persistNow();
    this.onRemoteChanged?.();
    return body;
  }

  /** Erases this account on the server, then forgets it here and turns sync off. */
  async deleteAccount() {
    await this.#load();
    if (this.#state.deviceToken) {
      await this.#call("DELETE", "account/");
    }
    this.#state = emptyState();
    this.#outbox = emptyOutbox();
    await this.#persistNow();
    await this.#saveJoinKey(null);
    const stored = (await getConfig()) || config;
    stored.syncHistory = false;
    config.syncHistory = false;
    await saveConfig(stored);
    this.onRemoteChanged?.();
  }

  /** The setting was turned off: nothing pending may leave the device later. */
  async dropPending() {
    await this.#load();
    this.#outbox = emptyOutbox();
    this.#state.remote = {};
    await this.#persistNow();
    this.onRemoteChanged?.();
  }
}

export const historySync = new HistorySync();
