'use strict';

/**
 * The update gate (openspec change app-update-gate): fetches the maintainer's
 * signed update policy and answers whether this build may run operations.
 *
 * Mirror of the Android app's gate (android/app/src/main/kotlin/org/duzgun/
 * eksiengelplus/gate/AppPolicy.kt and Gate.kt), ported from OpenOTT and
 * SportGuide TV. One policy file serves both clients; this one reads the
 * `chrome` or `firefox` channel. Change the rules on both sides together.
 *
 * Only a policy signed with one of PUBLIC_KEYS counts, so neither a network nor
 * the server can raise a minimum. A missing or unreachable policy blocks
 * nothing.
 */

export const POLICY_URL = "https://eksiengelplus.duzgun.org/policy/app-v1.json";
export const APP_ID = "org.duzgun.eksiengelplus";

/**
 * The maintainer's ES256 keys (X.509 SubjectPublicKeyInfo, base64), primary
 * first, then the offline backup. Same order as PolicyCodec.PUBLIC_KEYS in
 * AppPolicy.kt; tools/set-min-version.sh refuses to sign with any other.
 */
export const PUBLIC_KEYS = [
  "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmpAcIdckdfHQd2acThkHG5xroivAMU9E3BCg9sFGnlyHyABNb8gk/uF25CsbAca8Ds69cQDWxnvXa4GmCtma0A==",
  "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEcfxP4nYytvzTxBlqNESNX2ZoT+s0NikB49vzr5h0+Jkz1Pn9vVZ25/zr7Q+ozGnPn9yoVCpj6eis3uN2KdpOFw==",
];

export const STORE_PAGES = {
  chrome: "https://chromewebstore.google.com/detail/eksiengelplus/cakplcekfmnpelnchanlpoieoiodepdh",
  firefox: "https://addons.mozilla.org/firefox/addon/eksiengelplus",
};

export const CHECK_INTERVAL_MS = 6 * 3600 * 1000;
export const ALARM_NAME = "updateGate";
export const ALARM_PERIOD_MINUTES = 6 * 60;

const STORAGE_KEY = "updateGate";
const MAX_ENVELOPE = 64 * 1024;
const FETCH_TIMEOUT_MS = 15000;

const DEFAULT_MESSAGES = {
  required: "Bu EksiEngelPlus sürümü artık desteklenmiyor. İşlemlere devam etmek için eklentiyi güncelleyin.",
  revoked: "Bu EksiEngelPlus sürümünde bir sorun bulundu ve kullanımı durduruldu. İşlemlere devam etmek için eklentiyi güncelleyin.",
};

/** x.y.z -> major*10000 + minor*100 + patch, the Android versionCode (build.gradle.kts). */
export function versionCode(version) {
  const match = /^(\d+)\.(\d+)\.(\d+)$/.exec(String(version ?? ""));
  if (!match) return 0;
  const [major, minor, patch] = match.slice(1).map(Number);
  return major * 10000 + minor * 100 + patch;
}

/** The policy section this build reads: Firefox's manifest is the one with `gecko`. */
export function channelOf(manifest) {
  return manifest?.browser_specific_settings?.gecko ? "firefox" : "chrome";
}

function bytes(base64) {
  const binary = atob(base64);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

/**
 * A DER ECDSA signature as the 64 raw bytes (r || s) WebCrypto verifies.
 * openssl and Java's SHA256withECDSA both produce DER; WebCrypto takes only the
 * raw form. Null for anything that is not a well-formed P-256 signature.
 */
export function derToRaw(der) {
  let at = 0;
  const take = () => (at < der.length ? der[at++] : -1);
  if (take() !== 0x30) return null;
  const total = take();
  if (total < 0 || total & 0x80 || at + total !== der.length) return null;
  const raw = new Uint8Array(64);
  for (const offset of [0, 32]) {
    if (take() !== 0x02) return null;
    const length = take();
    if (length < 1 || length > 33 || at + length > der.length) return null;
    let int = der.subarray(at, at + length);
    at += length;
    while (int.length > 32 && int[0] === 0) int = int.subarray(1);
    if (int.length > 32) return null;
    raw.set(int, offset + 32 - int.length);
  }
  return at === der.length ? raw : null;
}

/** The payload as the gate uses it, or null. Mirror of PolicyCodec.parse. */
export function parsePolicy(text) {
  let root;
  try { root = JSON.parse(text); } catch { return null; }
  if (!root || typeof root !== "object" || String(root.v) !== "1") return null;
  const issuedAt = Number(root.issuedAt);
  if (!Number.isFinite(issuedAt) || typeof root.app !== "string") return null;
  const int = (value) => (Number.isInteger(Number(value)) && value !== null && value !== "" ? Number(value) : null);
  const channels = {};
  for (const [name, node] of Object.entries(root.channels && typeof root.channels === "object" ? root.channels : {})) {
    const min = int(node?.minVersionCode);
    if (min === null) continue;
    channels[name] = {
      minVersionCode: min,
      revokedVersionCodes: Array.isArray(node.revokedVersionCodes)
        ? node.revokedVersionCodes.map(int).filter((code) => code !== null)
        : [],
      latestVersionCode: int(node.latestVersionCode),
    };
  }
  const messages = {};
  for (const [lang, value] of Object.entries(root.message && typeof root.message === "object" ? root.message : {})) {
    if (typeof value === "string" && value.trim()) messages[lang] = value.slice(0, 500);
  }
  const expiresAt = int(root.expiresAt);
  return {
    issuedAt: issuedAt * 1000,
    expiresAt: expiresAt === null ? null : expiresAt * 1000,
    app: root.app,
    channels,
    messages,
  };
}

/** The policy inside [envelope] when a built-in key signed it and it names [appId]; null otherwise, never a throw. */
export async function verifyPolicy(envelope, appId = APP_ID, keys = PUBLIC_KEYS) {
  try {
    if (typeof envelope !== "string" || envelope.length > MAX_ENVELOPE) return null;
    const outer = JSON.parse(envelope);
    if (outer?.alg !== "ES256" || typeof outer.payload !== "string" || typeof outer.sig !== "string") return null;
    const payload = bytes(outer.payload);
    const signature = derToRaw(bytes(outer.sig));
    if (!signature) return null;
    let signed = false;
    for (const spki of keys) {
      const key = await crypto.subtle.importKey("spki", bytes(spki), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
      if (await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, signature, payload)) {
        signed = true;
        break;
      }
    }
    if (!signed) return null;
    const policy = parsePolicy(new TextDecoder().decode(payload));
    return policy?.app === appId ? policy : null;
  } catch {
    return null;
  }
}

/**
 * Folds a verified policy into the stored state. Mirror of Gate.accept: an
 * older or expired policy changes nothing, the minimum never goes down, and the
 * revocation list follows the newest policy so a mistaken one can be lifted.
 */
export function accept(state, policy, channel, nowMs) {
  const current = { stickyMin: 0, revoked: [], latest: 0, policyIssuedAt: 0, messages: {}, ...state };
  if (policy.issuedAt < current.policyIssuedAt) return current;
  if (policy.expiresAt !== null && policy.expiresAt <= nowMs) return current;
  const rules = policy.channels[channel];
  const stickyMin = Math.max(current.stickyMin, rules?.minVersionCode ?? 0);
  return {
    ...current,
    stickyMin,
    revoked: rules?.revokedVersionCodes ?? [],
    latest: Math.max(rules?.latestVersionCode ?? 0, stickyMin),
    policyIssuedAt: policy.issuedAt,
    messages: policy.messages,
  };
}

/** What this build may do. Mirror of Gate.evaluate, without the offline and tamper rules. */
export function evaluate(state, code) {
  const s = { stickyMin: 0, revoked: [], latest: 0, ...state };
  if (s.revoked.includes(code)) return { kind: "revoked", blocks: true, version: code };
  if (s.stickyMin > code) return { kind: "required", blocks: true, minimum: s.stickyMin };
  if (s.latest > code) return { kind: "available", blocks: false, latest: s.latest };
  return { kind: "allow", blocks: false };
}

/** The text a blocked build shows: the maintainer's, in Turkish where given. */
export function blockingMessage(state, decision) {
  const messages = state?.messages ?? {};
  return messages.tr ?? messages.en ?? DEFAULT_MESSAGES[decision.kind] ?? DEFAULT_MESSAGES.required;
}

export class UpdateGate {
  /** Every dependency is injectable so scripts/test-update-gate.mjs runs it in node. */
  constructor(deps = {}) {
    this.deps = deps;
  }

  get #storage() { return this.deps.storage ?? chrome.storage.local; }
  get #manifest() { return this.deps.manifest ?? chrome.runtime.getManifest(); }
  get #now() { return (this.deps.now ?? Date.now)(); }

  get channel() { return channelOf(this.#manifest); }
  get versionCode() { return versionCode(this.#manifest.version); }

  async state() {
    const stored = await this.#storage.get(STORAGE_KEY);
    return stored?.[STORAGE_KEY] ?? {};
  }

  async decision() {
    const state = await this.state();
    const decision = evaluate(state, this.versionCode);
    return { ...decision, message: decision.blocks ? blockingMessage(state, decision) : null };
  }

  async blocks() {
    return (await this.decision()).blocks;
  }

  /**
   * Fetches and applies the policy when the last attempt is older than
   * CHECK_INTERVAL_MS, or always with force. Network errors and bad signatures
   * leave the state as it was. True when a valid policy was accepted.
   */
  async refresh({ force = false } = {}) {
    const state = await this.state();
    const now = this.#now;
    if (!force && state.attemptAt && now - state.attemptAt >= 0 && now - state.attemptAt < CHECK_INTERVAL_MS) return false;
    await this.#storage.set({ [STORAGE_KEY]: { ...state, attemptAt: now } });
    let body = null;
    try {
      const fetchImpl = this.deps.fetch ?? fetch;
      const response = await fetchImpl(POLICY_URL, { cache: "no-store", signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
      if (response.ok) body = await response.text();
    } catch {
      // Offline, DNS, timeout: the last accepted policy stays in force.
    }
    const policy = body && await verifyPolicy(body);
    if (!policy) return false;
    const latest = await this.state();
    await this.#storage.set({ [STORAGE_KEY]: { ...accept(latest, policy, this.channel, now), attemptAt: now } });
    return true;
  }
}

export const updateGate = new UpdateGate();

/**
 * Gets the user onto the new version. Chrome can be asked to check its store
 * now; the background reloads into the update once it is downloaded
 * (runtime.onUpdateAvailable). Where the browser cannot be asked -- Firefox has
 * no requestUpdateCheck -- or has nothing newer, the store page opens.
 */
export async function startUpdate(gate = updateGate) {
  if (typeof chrome.runtime.requestUpdateCheck === "function") {
    try {
      const result = await chrome.runtime.requestUpdateCheck();
      if (result?.status === "update_available") return "downloading";
    } catch {
      // Throttled or unsupported: the store page still works.
    }
  }
  await chrome.tabs.create({ url: STORE_PAGES[gate.channel] });
  return "store";
}
