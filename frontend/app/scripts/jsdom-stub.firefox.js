// Firefox build only — substituted for the real assets/js/jsdom.js at package
// time. See scripts/ext.mjs.
//
// scrapingHandler.js imports JSDOM statically, so the module has to resolve in
// every build, but Firefox never reaches it: parseHTML() prefers the native
// DOMParser, which a Firefox background script always has. Chrome needs the
// real thing because MV3 service workers have no DOM APIs.
//
// The real bundle is 5.9 MB, and addons.mozilla.org refuses to validate any
// non-binary file over 5 MB ("File is too large to parse"), which blocks the
// whole submission.

export function JSDOM() {
  throw new Error(
    "JSDOM is not bundled in the Firefox build — DOMParser should have handled this."
  );
}
