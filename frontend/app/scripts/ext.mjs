#!/usr/bin/env node
/**
 * EksiEngelPlus extension tooling.
 *
 *   node scripts/ext.mjs switch <chrome|firefox>
 *   node scripts/ext.mjs check
 *   node scripts/ext.mjs changelog
 *   node scripts/ext.mjs version <patch|minor|major|x.y.z>
 *   node scripts/ext.mjs package
 *   node scripts/ext.mjs release <patch|minor|major|x.y.z|current>
 *
 * Chrome and Firefox ship the exact same files; only manifest.json differs.
 * manifest.chrome.json and manifest.firefox.json are the tracked sources of
 * truth, and manifest.json is generated from one of them.
 */

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { deflateRawSync } from "node:zlib";
import { fileURLToPath, pathToFileURL } from "node:url";

const APP_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const DIST_DIR = path.resolve(APP_DIR, "../publish/dist");
const REPO_ROOT = path.resolve(APP_DIR, "../..");

/**
 * The Android app records the same product version. It is JSON with a top-level
 * "version" field precisely so versionsIn() and rewriteVersion() consume it with
 * no special casing — see versionFiles(). Reading it is a file read, so `check`,
 * `version` and `package` still run with no JDK and no Android SDK installed.
 */
const ANDROID_VERSION_FILE = path.join(REPO_ROOT, "android", "version.json");

/**
 * The release notes, and the website page they also feed.
 *
 * changelog.js is the source: the extension's welcome page imports it directly,
 * and docs/changelog.json is generated from it so the site cannot fall behind
 * the product the way it did between 2.6.0 and 0.1.8 — four releases the page
 * never showed because updating it was a separate act nobody remembered.
 *
 * The pre-rename history has no entry in changelog.js and never will: those are
 * Ekşi Engel's releases, in a different numbering and a different format. They
 * live in changelog.legacy.json and are appended verbatim.
 */
const CHANGELOG_JS = path.join(APP_DIR, "assets", "js", "changelog.js");
const DOCS_CHANGELOG = path.join(REPO_ROOT, "docs", "changelog.json");
const DOCS_CHANGELOG_LEGACY = path.join(REPO_ROOT, "docs", "changelog.legacy.json");

/** Badge text per platform, matching the labels the two clients show. */
const DOCS_PLATFORM_BADGES = { extension: "Eklenti", app: "Uygulama" };

const BROWSERS = ["chrome", "firefox"];
const manifestFor = (browser) => path.join(APP_DIR, `manifest.${browser}.json`);
const ACTIVE_MANIFEST = path.join(APP_DIR, "manifest.json");

/** Files and directories that are tooling, not extension payload. */
const EXCLUDED = new Set([
  "package.json",
  "package-lock.json",
  "manifest.json",
  "manifest.chrome.json",
  "manifest.firefox.json",
  "scripts",
  "node_modules",
  ".DS_Store",
]);

/**
 * Payload files swapped out for a specific browser at package time.
 *
 * scrapingHandler.js imports JSDOM statically, so the module must resolve in
 * both builds, but Firefox never reaches it — parseHTML() prefers the native
 * DOMParser. Shipping the real 5.9 MB bundle to addons.mozilla.org fails
 * validation outright: it refuses to parse non-binary files over 5 MB.
 */
const OVERRIDES = {
  firefox: {
    "assets/js/jsdom.js": path.join(APP_DIR, "scripts/jsdom-stub.firefox.js"),
  },
};

/** addons.mozilla.org will not parse a non-binary file larger than this. */
const AMO_PARSE_LIMIT = 5 * 1024 * 1024;
const TEXT_FILE = /\.(js|json|css|html|txt|svg)$/i;

// ---------------------------------------------------------------- utilities

function fail(message) {
  console.error(`error: ${message}`);
  process.exit(1);
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (e) {
    fail(`${path.basename(file)} is not valid JSON: ${e.message}`);
  }
}

function parseSemver(v) {
  return /^\d+\.\d+\.\d+$/.test(String(v ?? "")) ? String(v).split(".").map(Number) : null;
}

function bumpSemver(current, spec) {
  if (parseSemver(spec)) return spec;
  const [major, minor, patch] = parseSemver(current) ?? fail(`current version "${current}" is not x.y.z`);
  if (spec === "major") return `${major + 1}.0.0`;
  if (spec === "minor") return `${major}.${minor + 1}.0`;
  if (spec === "patch") return `${major}.${minor}.${patch + 1}`;
  fail(`unknown version spec "${spec}" (expected patch|minor|major|x.y.z)`);
}

/** Every file that records the product version. manifest.json is generated. */
function versionFiles() {
  const files = [
    path.join(APP_DIR, "package.json"),
    path.join(APP_DIR, "package-lock.json"),
    ...BROWSERS.map(manifestFor),
    ANDROID_VERSION_FILE,
  ];
  if (fs.existsSync(ACTIVE_MANIFEST)) files.push(ACTIVE_MANIFEST);
  return files;
}

/** Every version string a single file records, as {label, value}. */
function versionsIn(file) {
  const json = readJson(file);
  const name = path.basename(file);
  const found = [];
  if (json.version !== undefined) found.push({ label: name, value: json.version });
  if (json.packages?.[""]?.version !== undefined) {
    found.push({ label: `${name} packages[""]`, value: json.packages[""].version });
  }
  if (found.length === 0) fail(`${name}: no version field`);
  return found;
}

/**
 * Rewrite `"version": "<current>"` in place. Text substitution rather than
 * re-serialising the JSON, because the manifests mix tab- and space-indented
 * lines and a reformat would bury the one-line bump in noise.
 * `"strict_min_version"` and `"lockfileVersion"` do not match: the pattern
 * requires the quote immediately before `version`.
 */
function rewriteVersion(file, current, next) {
  const before = fs.readFileSync(file, "utf8");
  const pattern = new RegExp(`("version"\\s*:\\s*)"${current.replaceAll(".", "\\.")}"`, "g");
  const hits = (before.match(pattern) ?? []).length;
  if (hits === 0) fail(`${path.basename(file)}: no "version": "${current}" to rewrite`);
  fs.writeFileSync(file, before.replace(pattern, `$1"${next}"`));
  return hits;
}

// -------------------------------------------------------------------- zip

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[i] = c;
  }
  return table;
})();

function crc32(buf) {
  let c = ~0;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (~c) >>> 0;
}

/**
 * Minimal ZIP writer. `zip` is not installed everywhere, so building the archive
 * here keeps local and CI output identical. Timestamps are pinned to the DOS
 * epoch and entries are sorted, so the same inputs produce the same bytes.
 */
function writeZip(outFile, entries) {
  const DOS_TIME = 0;
  const DOS_DATE = 0x0021; // 1980-01-01
  const local = [];
  const central = [];
  let offset = 0;

  for (const { name, data } of entries) {
    const nameBuf = Buffer.from(name, "utf8");
    const deflated = deflateRawSync(data, { level: 9 });
    const stored = deflated.length >= data.length;
    const body = stored ? data : deflated;
    const method = stored ? 0 : 8;
    const crc = crc32(data);

    const header = Buffer.alloc(30);
    header.writeUInt32LE(0x04034b50, 0);
    header.writeUInt16LE(20, 4); // version needed to extract
    header.writeUInt16LE(0, 6); // flags
    header.writeUInt16LE(method, 8);
    header.writeUInt16LE(DOS_TIME, 10);
    header.writeUInt16LE(DOS_DATE, 12);
    header.writeUInt32LE(crc, 14);
    header.writeUInt32LE(body.length, 18);
    header.writeUInt32LE(data.length, 22);
    header.writeUInt16LE(nameBuf.length, 26);
    header.writeUInt16LE(0, 28); // extra field length
    local.push(header, nameBuf, body);

    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50, 0);
    entry.writeUInt16LE(20, 4); // version made by
    entry.writeUInt16LE(20, 6); // version needed to extract
    entry.writeUInt16LE(0, 8); // flags
    entry.writeUInt16LE(method, 10);
    entry.writeUInt16LE(DOS_TIME, 12);
    entry.writeUInt16LE(DOS_DATE, 14);
    entry.writeUInt32LE(crc, 16);
    entry.writeUInt32LE(body.length, 20);
    entry.writeUInt32LE(data.length, 24);
    entry.writeUInt16LE(nameBuf.length, 28);
    entry.writeUInt16LE(0, 30); // extra field length
    entry.writeUInt16LE(0, 32); // comment length
    entry.writeUInt16LE(0, 34); // disk number start
    entry.writeUInt16LE(0, 36); // internal attributes
    entry.writeUInt32LE((0o100644 << 16) >>> 0, 38); // external attributes: regular file, 644
    entry.writeUInt32LE(offset, 42);
    central.push(entry, nameBuf);

    offset += header.length + nameBuf.length + body.length;
  }

  const centralBuf = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4); // this disk
  eocd.writeUInt16LE(0, 6); // disk with central directory
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20); // comment length

  fs.writeFileSync(outFile, Buffer.concat([...local, centralBuf, eocd]));
}

/** Every payload file under frontend/app, as zip-relative posix paths. */
function payloadFiles() {
  const out = [];
  const walk = (dir, prefix) => {
    const entries = fs
      .readdirSync(dir, { withFileTypes: true })
      .sort((a, b) => a.name.localeCompare(b.name));
    for (const entry of entries) {
      if (EXCLUDED.has(entry.name)) continue;
      const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
      if (entry.isDirectory()) walk(path.join(dir, entry.name), rel);
      else if (entry.isFile()) out.push(rel);
    }
  };
  walk(APP_DIR, "");
  return out;
}

// --------------------------------------------------------------- commands

function cmdSwitch(browser) {
  if (!BROWSERS.includes(browser)) fail(`switch expects one of: ${BROWSERS.join(", ")}`);
  fs.copyFileSync(manifestFor(browser), ACTIVE_MANIFEST);
  console.log(`manifest.json <- manifest.${browser}.json`);
}

/**
 * docs/changelog.json, as it should be: changelog.js then the legacy tail.
 *
 * Concatenated, never sorted. The project renamed and restarted at 0.1.0, so
 * "newest first" and "highest version first" stopped being the same ordering —
 * 3.2.0 is older than 0.1.2 and any comparison of the two numbers says the
 * opposite. Each list is already newest-first on its own.
 */
function buildDocsChangelog() {
  const { releaseNotes, noChangesNote } = changelog;

  const modern = Object.entries(releaseNotes).flatMap(([version, entry]) => {
    // No date means not released yet. The notes for the next version are often
    // written while it is still being built, and docs/ is a live site: listing
    // one would announce a release nobody can install. cmdVersion stamps the
    // date when the number is bumped, which is when it becomes real.
    if (!entry.date) return [];

    const notes = [];
    for (const [platform, badge] of Object.entries(DOCS_PLATFORM_BADGES)) {
      const lines = entry[platform];
      // Omitted means the platform did not exist for this release; only an
      // empty array is the deliberate "nothing changed here".
      if (!lines) continue;
      const text = lines.length ? lines : [noChangesNote];
      for (const line of text) notes.push(`[${badge}] ${line}`);
    }
    return [{ name: "", notes, pub_date: entry.date, version }];
  });

  const legacy = readJson(DOCS_CHANGELOG_LEGACY);
  return JSON.stringify([...modern, ...legacy], null, 2) + "\n";
}

/**
 * Writes docs/changelog.json, or with `check` only reports that it is stale.
 *
 * `check` is what cmdCheck calls, so a note added to changelog.js without
 * regenerating fails the build rather than shipping a site that disagrees with
 * the extension about what the release contained.
 */
function cmdChangelog({ check = false } = {}) {
  const wanted = buildDocsChangelog();
  const current = fs.existsSync(DOCS_CHANGELOG)
    ? fs.readFileSync(DOCS_CHANGELOG, "utf8")
    : null;

  if (current === wanted) {
    if (!check) console.log("docs/changelog.json already up to date");
    return;
  }
  if (check) fail("docs/changelog.json is stale; run `npm run changelog`");

  fs.writeFileSync(DOCS_CHANGELOG, wanted);
  const count = JSON.parse(wanted).length;
  console.log(`docs/changelog.json <- changelog.js + legacy (${count} releases)`);
}

/** Assert every recorded version agrees, and return it. */
function cmdCheck() {
  const found = versionFiles().flatMap(versionsIn);
  for (const { label, value } of found) {
    if (!parseSemver(value)) fail(`${label}: version "${value}" is not x.y.z`);
  }

  const distinct = new Set(found.map((f) => f.value));
  if (distinct.size !== 1) {
    for (const { label, value } of found) console.error(`  ${label}: ${value}`);
    fail(`version mismatch across ${found.length} locations`);
  }

  // The generated manifest must be a byte-for-byte copy of one of the variants,
  // otherwise someone has hand-edited it away from its source of truth.
  if (fs.existsSync(ACTIVE_MANIFEST)) {
    const active = fs.readFileSync(ACTIVE_MANIFEST);
    if (!BROWSERS.some((b) => fs.readFileSync(manifestFor(b)).equals(active))) {
      fail("manifest.json matches neither variant; run `npm run switch:chrome` or `switch:firefox`");
    }
  }

  // The site is generated from changelog.js, so a stale docs/changelog.json is
  // the same class of drift as a hand-edited manifest.json: something that is
  // supposed to be a copy no longer is.
  cmdChangelog({ check: true });

  const version = [...distinct][0];
  console.log(`ok: version ${version} consistent across ${found.length} locations`);
  return version;
}

/**
 * Records the release date on the version's changelog entry.
 *
 * The date is what promotes a note from "written" to "shipped": until it is
 * there the entry is deliberately kept off the website. Stamped at the bump
 * rather than at the tag because `version:patch` and `release` are separate
 * acts here, and only the bump is guaranteed to happen exactly once.
 *
 * Silent when there is no entry to stamp. A missing note is caught by the
 * Android side's drift test, which is the one place that knows whether the
 * shipping version has anything to say.
 */
function stampReleaseDate(version) {
  const source = fs.readFileSync(CHANGELOG_JS, "utf8");
  const opening = new RegExp(`("${version.replace(/\./g, "\\.")}"\\s*:\\s*\\{)`);
  if (!opening.test(source)) return false;
  // Already dated: a re-run must not move a date that has been published.
  if (new RegExp(`"${version.replace(/\./g, "\\.")}"\\s*:\\s*\\{\\s*date:`).test(source)) return false;

  const today = new Date().toISOString().slice(0, 10);
  fs.writeFileSync(CHANGELOG_JS, source.replace(opening, `$1\n    date: "${today}",`));
  // The module was imported once at startup and is what buildDocsChangelog
  // reads; without this the regeneration that follows would emit the file as it
  // was before the stamp, and `check` would then call its own output stale.
  changelog.releaseNotes[version].date = today;
  return true;
}

function cmdVersion(spec) {
  if (!spec) fail("version expects patch|minor|major|x.y.z");
  const current = cmdCheck();
  const next = bumpSemver(current, spec);
  if (next === current) fail(`already at ${current}`);

  const files = versionFiles();
  const total = files.reduce((n, file) => n + rewriteVersion(file, current, next), 0);
  console.log(`${current} -> ${next} (${total} occurrences in ${files.length} files)`);

  if (stampReleaseDate(next)) console.log(`changelog.js: dated ${next}`);
  else console.warn(`warning: changelog.js has no undated entry for ${next}`);
  // Regenerated here rather than left for `check` to complain about: the date
  // just changed, so the site's copy is stale by construction.
  cmdChangelog();
  return next;
}

function cmdPackage() {
  const version = cmdCheck();
  fs.rmSync(DIST_DIR, { recursive: true, force: true });
  fs.mkdirSync(DIST_DIR, { recursive: true });

  const shared = payloadFiles().map((name) => ({
    name,
    data: fs.readFileSync(path.join(APP_DIR, name)),
  }));

  for (const browser of BROWSERS) {
    const overrides = OVERRIDES[browser] ?? {};
    const entries = [
      { name: "manifest.json", data: fs.readFileSync(manifestFor(browser)) },
      ...shared.map(({ name, data }) =>
        overrides[name] ? { name, data: fs.readFileSync(overrides[name]) } : { name, data }
      ),
    ].sort((a, b) => a.name.localeCompare(b.name));

    if (browser === "firefox") {
      const tooBig = entries.filter(
        (e) => TEXT_FILE.test(e.name) && e.data.length > AMO_PARSE_LIMIT
      );
      for (const e of tooBig) {
        console.error(`  ${e.name}: ${Math.round(e.data.length / 1024 / 1024)} MB`);
      }
      if (tooBig.length) {
        fail("files above 5 MB will fail addons.mozilla.org validation; add an entry to OVERRIDES");
      }
    }

    const out = path.join(DIST_DIR, `eksiengelplus-${version}-${browser}.zip`);
    writeZip(out, entries);
    const kb = Math.round(fs.statSync(out).size / 1024);
    const swapped = Object.keys(overrides).length;
    const note = swapped ? `, ${swapped} substituted` : "";
    console.log(
      `${path.relative(process.cwd(), out)}  (${entries.length} files${note}, ${kb} KB)`
    );
  }
}

/**
 * Bump, commit and tag — or, with "current", tag what is already recorded.
 *
 * The "current" path exists because the ordinary one could not finish a release
 * someone had already half-made. `npm run version:patch` writes the new number
 * into all seven files and stops, by design, so the bump can be reviewed. If the
 * tag is not cut in the same sitting, the repository is left at a version that
 * ships nowhere: `release patch` would bump *again* and skip the version that
 * was prepared, and `release 0.1.7` fails on "already at 0.1.7" because
 * cmdVersion refuses a no-op. There was no way to say "release what is here".
 *
 * It takes no shortcut with safety: the tree must still be clean, the tag must
 * still not exist, and the seven versions must still agree. It only skips the
 * rewrite and the commit, because there is nothing to rewrite and a commit with
 * no change is not a release note, it is noise.
 */
function cmdRelease(spec) {
  const git = (...args) => execFileSync("git", args, { cwd: APP_DIR, encoding: "utf8" }).trim();

  // Named here rather than left to cmdVersion, whose message cannot mention
  // "current" — that spec is meaningless to a bare version rewrite.
  if (!spec) fail("release expects patch|minor|major|x.y.z|current");

  if (git("status", "--porcelain")) {
    fail("working tree is dirty; commit or stash before releasing");
  }

  const releasingCurrent = spec === "current";
  // cmdCheck asserts all seven agree; cmdVersion runs it first and then rewrites.
  const version = releasingCurrent ? cmdCheck() : cmdVersion(spec);
  const tag = `v${version}`;
  if (git("tag", "--list", tag)) fail(`tag ${tag} already exists`);

  if (releasingCurrent) {
    git("tag", "-a", tag, "-m", tag);
    console.log(`\ntagged ${tag} at HEAD (version already recorded; nothing to commit)`);
  } else {
    for (const file of versionFiles()) {
      if (file === ACTIVE_MANIFEST) continue; // generated, untracked
      git("add", file);
    }
    // The bump dated the release and regenerated the site's copy. Both are part
    // of the release, and leaving them out would land a commit whose own
    // `check` fails.
    git("add", CHANGELOG_JS, DOCS_CHANGELOG);
    git("commit", "-m", `chore: release ${tag}`);
    git("tag", "-a", tag, "-m", tag);
    console.log(`\ncommitted and tagged ${tag}`);
  }

  console.log("push with:  git push origin HEAD --follow-tags");
}

// ------------------------------------------------------------------- main

/*
 * changelog.js is an ES module of plain data, so it is imported rather than
 * parsed. Loaded here, once, and read synchronously afterwards: making cmdCheck
 * async would have made cmdVersion, cmdPackage and cmdRelease async with it,
 * for a file that is a few kilobytes of literals.
 */
const changelog = await import(pathToFileURL(CHANGELOG_JS).href);

const [command, arg] = process.argv.slice(2);
switch (command) {
  case "switch":
    cmdSwitch(arg);
    break;
  case "check":
    cmdCheck();
    break;
  case "changelog":
    cmdChangelog();
    break;
  case "version":
    cmdVersion(arg);
    break;
  case "package":
    cmdPackage();
    break;
  case "release":
    cmdRelease(arg);
    break;
  default:
    fail("usage: ext.mjs <switch|check|changelog|version|package|release> [arg]");
}
