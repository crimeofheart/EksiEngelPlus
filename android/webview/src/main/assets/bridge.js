/*
 * Ported from frontend/app/assets/js/script.js.
 *
 * Same items, same targets, same Turkish labels. Three things differ, each for a
 * reason recorded in openspec/changes/android-browsing-shell:
 *
 *   1. chrome.runtime.sendMessage -> EksiEngelPlus.postMessage (origin-scoped).
 *   2. Config is baked into the preamble by the host instead of being read
 *      asynchronously, so a label can never render before its value arrives.
 *   3. waitForElm is replaced by a persistent observer, because it disconnects
 *      after its first match and so never augments anything Ekşi renders later
 *      via XHR — very visible in a WebView, where the user never reloads.
 *
 * The host prepends: window.__EKSIENGEL_CONFIG__ and window.__EKSIENGEL_ICON__.
 */
(function () {
  "use strict";

  if (window.__eksiEngelBridgeLoaded) return;
  window.__eksiEngelBridgeLoaded = true;

  var CONFIG = window.__EKSIENGEL_CONFIG__ || {};
  var ICON = window.__EKSIENGEL_ICON__ || "";
  var MARK = "data-eksiengel-processed";

  // enums.js pks. Shared database keys -- must not drift.
  var BanSource = { SINGLE: 1, FAV: 2, FOLLOW: 3, TITLE: 6 };
  var BanMode = { BAN: 1, UNDOBAN: 2 };
  var TargetType = { USER: 1, TITLE: 2, MUTE: 3, FOLLOW: 4 };
  var ClickSource = { ENTRY: 1, PROFILE: 2, QUESTION: 3, TITLE: 6 };
  var TimeSpecifier = { LAST_24_H: 1, ALL: 5 };

  var reqId = 0;

  function send(type, payload) {
    reqId += 1;
    try {
      EksiEngelPlus.postMessage(
        JSON.stringify({ v: 1, type: type, reqId: String(reqId), payload: payload || {} })
      );
    } catch (e) {
      /* bridge absent: not an allowed origin */
    }
  }

  function enqueue(payload) {
    send("enqueueAction", payload);
    toast("EksiEngelPlus, istediğiniz işlemi sıraya ekledi.");
  }

  /**
   * A small overlay of our own.
   *
   * script.js:63-69 reuses the site's #user-notifications component, which on
   * mobile renders full-width with a large call-to-action -- far too heavy for a
   * message that only says "queued".
   */
  function toast(text) {
    var el = document.createElement("div");
    el.textContent = text;
    el.style.cssText = [
      "position:fixed",
      "left:50%",
      "bottom:24px",
      "transform:translateX(-50%)",
      "max-width:88vw",
      "padding:8px 14px",
      "border-radius:18px",
      "background:rgba(32,32,32,0.92)",
      "color:#fff",
      "font-size:13px",
      "line-height:1.35",
      "z-index:2147483647",
      "box-shadow:0 2px 8px rgba(0,0,0,0.3)",
      "pointer-events:none",
      "opacity:0",
      "transition:opacity 150ms ease"
    ].join(";");
    document.body.appendChild(el);
    requestAnimationFrame(function () { el.style.opacity = "1"; });
    setTimeout(function () {
      el.style.opacity = "0";
      setTimeout(function () { el.remove(); }, 200);
    }, 2200);
  }

  function item(label) {
    var li = document.createElement("li");
    li.innerHTML =
      '<a href="javascript:void(0);"><img src="' + ICON +
      '" style="width:16px;height:16px;vertical-align:middle;margin-right:5px;"> ' +
      label + "</a>";
    return li;
  }

  function muteWord(base, muted) {
    return CONFIG.enableMute ? muted : base;
  }

  // ------------------------------------------------------------- injectors

  /** Title menu: two items, mirroring script.js:184-265. */
  function injectTitleMenu(menu) {
    var title = document.getElementById("title");
    if (!title) return;
    var slug = title.getAttribute("data-slug");
    var id = title.getAttribute("data-id");
    if (!slug || !id) return;

    var last24 = item("başlıktakileri engelle (son 24 saatte)");
    var all = item("başlıktakileri engelle (tümü)");

    last24.onclick = function () {
      enqueue({
        banSource: BanSource.TITLE, banMode: BanMode.BAN,
        targetType: CONFIG.enableMute ? TargetType.MUTE : TargetType.USER,
        clickSource: ClickSource.TITLE,
        titleName: slug, titleId: Number(id),
        timeSpecifier: TimeSpecifier.LAST_24_H
      });
    };
    all.onclick = function () {
      enqueue({
        banSource: BanSource.TITLE, banMode: BanMode.BAN,
        targetType: CONFIG.enableMute ? TargetType.MUTE : TargetType.USER,
        clickSource: ClickSource.TITLE,
        titleName: slug, titleId: Number(id),
        timeSpecifier: TimeSpecifier.ALL
      });
    };

    if (menu.childElementCount > 1) {
      menu.insertBefore(last24, menu.children[menu.childElementCount - 1]);
      menu.insertBefore(all, menu.children[menu.childElementCount - 1]);
    } else {
      menu.appendChild(last24);
      menu.appendChild(all);
    }
  }

  /**
   * Four .dropdown-menu elements render per page, so position cannot identify
   * the entry menu. Matching on contents is required, not defensive
   * (script.js:315).
   */
  var ENTRY_MENU_MARKERS = ["engelle", "modlog", "şikayet", "mesaj"];

  function isEntryMenu(menu) {
    var text = (menu.textContent || "").toLowerCase();
    for (var i = 0; i < ENTRY_MENU_MARKERS.length; i++) {
      if (text.indexOf(ENTRY_MENU_MARKERS[i]) !== -1) return true;
    }
    return false;
  }

  function injectEntryMenu(menu) {
    if (!isEntryMenu(menu)) return;

    var li = menu.closest("li[data-id]") || menu.closest("article[data-id]");
    if (!li) return;
    var authorName = li.getAttribute("data-author");
    var authorId = li.getAttribute("data-author-id");
    var entryId = li.getAttribute("data-id");
    if (!authorName || !entryId) return;

    var clickSource =
      location.pathname.split("/")[1] === "sorunsal" ? ClickSource.QUESTION : ClickSource.ENTRY;
    var entryUrl = location.origin + "/entry/" + entryId;
    var targetType = CONFIG.enableMute ? TargetType.MUTE : TargetType.USER;

    var banUser = item(muteWord("yazarı engelle", "yazarı sessize al"));
    var banFav = item("favlayanları engelle");
    var banFollow = item(muteWord("takipçilerini engelle", "takipçilerini sessize al"));

    banUser.onclick = function () {
      enqueue({
        banSource: BanSource.SINGLE, banMode: BanMode.BAN, targetType: targetType,
        clickSource: clickSource, authorName: authorName,
        authorId: authorId ? Number(authorId) : null, entryUrl: entryUrl
      });
    };
    banFav.onclick = function () {
      enqueue({
        banSource: BanSource.FAV, banMode: BanMode.BAN, targetType: targetType,
        clickSource: clickSource, entryUrl: entryUrl, entryId: Number(entryId)
      });
    };
    banFollow.onclick = function () {
      enqueue({
        banSource: BanSource.FOLLOW, banMode: BanMode.BAN, targetType: targetType,
        clickSource: clickSource, authorName: authorName,
        authorId: authorId ? Number(authorId) : null
      });
    };

    menu.appendChild(banUser);
    menu.appendChild(banFav);
    menu.appendChild(banFollow);
  }

  /** Profile buttons, mirroring script.js:425-573. */
  function injectProfile(container) {
    if (location.pathname.indexOf("/biri/") !== 0) return;
    var nickHolder = document.querySelector("[data-nick]");
    var who = document.getElementById("who");
    if (!nickHolder) return;
    var nick = nickHolder.getAttribute("data-nick");
    var id = who ? Number(who.getAttribute("value")) : null;
    var targetType = CONFIG.enableMute ? TargetType.MUTE : TargetType.USER;

    // The site's own red block button is removed so ours is the single path
    // (script.js:489) -- two buttons doing almost-the-same thing is worse.
    var native = document.getElementById("button-blocked-link");
    if (native) native.remove();

    var ban = item(muteWord("engelle", "sessize al"));
    var banTitles = item("başlıklarını engelle");
    var banFollowers = item(muteWord("takipçilerini engelle", "takipçilerini sessize al"));

    ban.onclick = function () {
      enqueue({
        banSource: BanSource.SINGLE, banMode: BanMode.BAN, targetType: targetType,
        clickSource: ClickSource.PROFILE, authorName: nick, authorId: id
      });
    };
    banTitles.onclick = function () {
      enqueue({
        banSource: BanSource.SINGLE, banMode: BanMode.BAN, targetType: TargetType.TITLE,
        clickSource: ClickSource.PROFILE, authorName: nick, authorId: id
      });
    };
    banFollowers.onclick = function () {
      enqueue({
        banSource: BanSource.FOLLOW, banMode: BanMode.BAN, targetType: targetType,
        clickSource: ClickSource.PROFILE, authorName: nick, authorId: id
      });
    };

    container.appendChild(ban);
    container.appendChild(banTitles);
    container.appendChild(banFollowers);
  }

  /**
   * Hides Ekşi's "open in our app" interstitial.
   *
   * It is injected client-side, not present in the served HTML, so there is no
   * stable class name to rely on -- it did not appear in any captured fixture.
   * Anchored on content instead: a fixed or sticky overlay that mentions both the
   * app and continuing. Narrow on purpose, so ordinary sticky UI is untouched.
   *
   * Precedent for hiding site chrome at the user's request is the extension's own
   * banPremiumIcons. Gated on config so it stays the user's choice.
   */
  var APP_PROMO_SELECTORS = [
    ".app-download-banner",
    ".mobile-app-banner",
    "#app-banner",
    ".smart-app-banner",
    "[class*=app-promo]",
    "[class*=appPromo]"
  ];

  function looksLikeAppPromo(el) {
    var text = (el.textContent || "").toLowerCase();
    if (text.length > 400) return false;             // too big to be the banner
    var mentionsApp = text.indexOf("uygulama") !== -1;
    var offersContinue = text.indexOf("devam et") !== -1 || text.indexOf("tarayıcı") !== -1;
    return mentionsApp && offersContinue;
  }

  function hideAppPromo() {
    if (CONFIG.hideAppPromo === false) return;

    for (var i = 0; i < APP_PROMO_SELECTORS.length; i++) {
      var known = document.querySelectorAll(APP_PROMO_SELECTORS[i]);
      for (var j = 0; j < known.length; j++) known[j].style.display = "none";
    }

    // Content-anchored fallback, restricted to overlays so normal page content
    // can never be caught by it.
    var candidates = document.querySelectorAll("div,section,aside");
    for (var k = 0; k < candidates.length; k++) {
      var el = candidates[k];
      if (el.dataset.eksiengelPromoChecked) continue;
      var pos = "";
      try { pos = window.getComputedStyle(el).position; } catch (e) { continue; }
      if (pos !== "fixed" && pos !== "sticky") continue;
      el.dataset.eksiengelPromoChecked = "1";
      if (looksLikeAppPromo(el)) el.style.display = "none";
    }
  }

  /** Premium badge hiding, script.js:107-178. */
  function hideBadges() {
    if (!CONFIG.banPremiumIcons) return;
    var sel = ".eksico.subscriber-badge, .eksico.verified-badge";
    var nodes = document.querySelectorAll(sel);
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i].parentNode) nodes[i].parentNode.style.display = "none";
    }
  }

  // --------------------------------------------------------- the observer

  /**
   * The site's share menu offers per-network destinations only. The Android
   * share sheet covers whatever the user actually has installed, which is the
   * interaction they expect, so ours goes first.
   */
  var SHARE_MARKERS = ["paylaş", "kopyala"];

  function isShareMenu(menu) {
    var text = (menu.textContent || "").toLowerCase();
    if (isEntryMenu(menu)) return false;          // that is the block menu
    for (var i = 0; i < SHARE_MARKERS.length; i++) {
      if (text.indexOf(SHARE_MARKERS[i]) !== -1) return true;
    }
    return false;
  }

  function injectShareMenu(menu) {
    if (!isShareMenu(menu)) return;

    var li = menu.closest("li[data-id]") || menu.closest("article[data-id]");
    var entryId = li && li.getAttribute("data-id");
    var url = entryId ? location.origin + "/entry/" + entryId : location.href;

    var share = item("paylaş");
    share.onclick = function () {
      send("share", { url: url, title: document.title || "ekşi sözlük" });
    };
    // Above the site's own options, not appended after them.
    if (menu.firstChild) menu.insertBefore(share, menu.firstChild);
    else menu.appendChild(share);
  }

  var injectors = [
    { selector: "#in-topic-search-options", apply: injectTitleMenu },
    { selector: ".dropdown-menu", apply: injectEntryMenu },
    { selector: ".dropdown-menu", apply: injectShareMenu },
    { selector: ".profile-buttons", apply: injectProfile }
  ];

  function scan() {
    for (var i = 0; i < injectors.length; i++) {
      var inj = injectors[i];
      // Per-injector mark: two injectors share the .dropdown-menu selector, so a
      // single shared mark would let whichever ran first consume the node.
      var mark = MARK + "-" + i;
      var nodes = document.querySelectorAll(inj.selector + ":not([" + mark + '="true"])');
      for (var j = 0; j < nodes.length; j++) {
        var node = nodes[j];
        // Marked before applying: an injector that throws must not be retried
        // forever on every mutation.
        node.setAttribute(mark, "true");
        try {
          inj.apply(node);
        } catch (e) {
          send("log", { level: "warn", tag: "bridge", msg: String(e) });
        }
      }
    }
    hideBadges();
    hideAppPromo();
  }

  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function () {
      // Trailing debounce so a 200-node insert triggers one pass, not 200.
      setTimeout(function () {
        scheduled = false;
        scan();
      }, 50);
    });
  }

  new MutationObserver(schedule).observe(document.documentElement, {
    childList: true,
    subtree: true
  });

  // pushState and replaceState mutate nothing, so the observer never sees
  // Ekşi's in-page navigation without this.
  ["pushState", "replaceState"].forEach(function (name) {
    var original = history[name];
    history[name] = function () {
      var r = original.apply(this, arguments);
      schedule();
      return r;
    };
  });
  window.addEventListener("popstate", schedule);

  // Host -> page.
  window.__eksiEngelOnMessage = function (raw) {
    var msg;
    try { msg = JSON.parse(raw); } catch (e) { return; }
    if (msg.type === "configChanged") {
      CONFIG = msg.payload || {};
      // Re-render labels in place rather than waiting for a reload.
      for (var n = 0; n < injectors.length; n++) {
        var mk = MARK + "-" + n;
        var marked = document.querySelectorAll("[" + mk + '="true"]');
        for (var i = 0; i < marked.length; i++) marked[i].removeAttribute(mk);
      }
      scan();
    } else if (msg.type === "toast") {
      toast(msg.payload && msg.payload.text);
    } else if (msg.type === "hideAuthors") {
      var ids = (msg.payload && msg.payload.ids) || [];
      for (var k = 0; k < ids.length; k++) {
        var els = document.querySelectorAll('[data-author-id="' + ids[k] + '"]');
        for (var m = 0; m < els.length; m++) {
          var row = els[m].closest("li[data-id]") || els[m].closest("article[data-id]") || els[m];
          row.style.display = "none";
        }
      }
    }
  };

  scan();
})();
