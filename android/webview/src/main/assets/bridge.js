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
  // Everything we add carries this, so a re-render can take our own items back
  // out without touching the site's.
  var ITEM_MARK = "data-eksiengel-item";

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

  /**
   * Collapses the containers the blocked ad hosts would have filled.
   *
   * Blocking the requests leaves the slots behind, and an empty reserved slot is
   * arguably worse than an ad: it is a hole in the page with no explanation.
   *
   * A stylesheet rather than a scan, injected at document start, so the slots
   * never occupy space at first paint instead of collapsing a moment later.
   * Every selector below was taken from the markup Ekşi actually ships.
   */
  function hideAdSlots() {
    var css = [
      ".ads",
      ".ad-banner",
      ".ad-double-click",
      ".ad-double-click-centered",
      ".bottom-ads",
      ".sticky-ad",
      ".under-top-ad",
      ".yeni-reklam",
      ".adrazzi-sponsored-entry",
      ".mobile-sponsored-entry",
      ".mobile-inread-ad-not-loaded",
      ".networkad-inread-video-ad",
      '[class*="nativespot-unit"]',
      "ins.adsbygoogle"
    ].join(",") + "{display:none !important;}";

    var style = document.createElement("style");
    style.setAttribute("data-eksiengel-adstyle", "true");
    style.textContent = css;
    // documentElement, not head: at document start there may not be a head yet.
    (document.head || document.documentElement).appendChild(style);
  }

  hideAdSlots();


  /**
   * Collapses containers that the blocked hosts would have filled.
   *
   * The stylesheet handles slots the site marks as ads. This handles the ones it
   * does not: #aside reserves seventy pixels for a sidebar unit and, with the
   * request dropped, stays on the page as a hole.
   *
   * Collapsed only while genuinely empty, and un-collapsed if content appears, so
   * a container the site later uses for something real is not lost. Re-checked on
   * every pass because these fill in late, which is why the gaps came back after
   * scrolling.
   */
  var EMPTY_WHEN_BLOCKED = "#aside, .ads, .sticky-ad, .bottom-ads, .under-top-ad";

  function collapseEmptyAdContainers() {
    var nodes = document.querySelectorAll(EMPTY_WHEN_BLOCKED);
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var hasText = (el.textContent || "").trim().length > 0;
      var hasMedia = !!el.querySelector("img,svg,video,canvas,picture,iframe");
      if (!hasText && !hasMedia) {
        el.style.setProperty("display", "none", "important");
      } else if (el.style.getPropertyValue("display") === "none") {
        el.style.removeProperty("display");
      }
    }
  }

  function item(label) {
    var li = document.createElement("li");
    li.setAttribute(ITEM_MARK, "true");
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

    /*
     * Only where the site itself offers a relation.
     *
     * Ekşi renders no .relation-link on your own profile, because there is
     * nothing to block or follow there -- so keying off its buttons is what keeps
     * "engelle" and "takipçilerini engelle" off your own page. Keying off the
     * /biri/ path alone offered the user the chance to block themselves.
     *
     * This is the gate script.js:467 relies on, and it self-maintains: any page
     * state where the site withdraws the relation buttons withdraws ours too,
     * without us having to know who is logged in.
     */
    if (container.querySelectorAll(".relation-link").length === 0) return false;

    var nickHolder = document.querySelector("[data-nick]");
    var who = document.getElementById("who");
    if (!nickHolder) return false;
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

  /**
   * Shallow on purpose.
   *
   * An interstitial that covers the page is attached at or near the body; a
   * position:fixed element nested six levels inside a list row is not a thing the
   * site does. Querying "div,section,aside" instead meant every row of a
   * follower list was a candidate, and each candidate cost a getComputedStyle --
   * a style resolution per row, on every mutation, on the longest pages in the
   * app.
   */
  var PROMO_CANDIDATE_SELECTOR = [
    "body > div", "body > section", "body > aside",
    "body > div > div", "body > div > section", "body > div > aside"
  ].join(",");

  function hideAppPromo() {
    if (CONFIG.hideAppPromo === false) return;

    for (var i = 0; i < APP_PROMO_SELECTORS.length; i++) {
      var known = document.querySelectorAll(APP_PROMO_SELECTORS[i]);
      for (var j = 0; j < known.length; j++) known[j].style.display = "none";
    }

    // Content-anchored fallback, restricted to overlays so normal page content
    // can never be caught by it.
    var candidates = document.querySelectorAll(PROMO_CANDIDATE_SELECTOR);
    for (var k = 0; k < candidates.length; k++) {
      var el = candidates[k];
      if (el.dataset.eksiengelPromoChecked) continue;
      // Marked before the position test, not after it. Marking only the
      // fixed/sticky ones left every ordinary element unmarked and therefore
      // re-examined on every single pass -- the whole document, forever.
      el.dataset.eksiengelPromoChecked = "1";
      var pos = "";
      try { pos = window.getComputedStyle(el).position; } catch (e) { continue; }
      if (pos !== "fixed" && pos !== "sticky") continue;
      if (looksLikeAppPromo(el)) el.style.display = "none";
    }
  }

  /** Premium badge hiding, script.js:107-178. */
  var BADGE_MARK = "data-eksiengel-badge";

  function hideBadges() {
    if (!CONFIG.banPremiumIcons) return;
    // :not([mark]) so a badge is hidden once rather than re-hidden on every pass.
    // Re-writing style.display on an already-hidden node is a style invalidation
    // per badge per mutation, which on a long list is most of the page.
    var sel = ".eksico.subscriber-badge:not([" + BADGE_MARK + "])," +
      ".eksico.verified-badge:not([" + BADGE_MARK + "])";
    var nodes = document.querySelectorAll(sel);
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].setAttribute(BADGE_MARK, "true");
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
          // An injector returns false for "not ready yet" -- the container is
          // present but the part it keys off has not rendered. Unmark so a later
          // mutation retries it. A throw still leaves the mark, so a broken
          // injector cannot loop.
          if (inj.apply(node) === false) node.removeAttribute(mark);
        } catch (e) {
          send("log", { level: "warn", tag: "bridge", msg: String(e) });
        }
      }
    }
    hideBadges();
    hideAppPromo();
    collapseEmptyAdContainers();
  }

  /**
   * A canary on injection cost.
   *
   * The observer fires on every XHR append, so a scan that grows with page size
   * is the failure mode to watch for. Silent unless a pass is slow enough to be
   * felt, which keeps it useful rather than noisy.
   */
  function timedScan() {
    var t0 = (window.performance && performance.now) ? performance.now() : Date.now();
    scan();
    var t1 = (window.performance && performance.now) ? performance.now() : Date.now();
    if (t1 - t0 > 30) {
      console.warn("eksiengel: scan took " + Math.round(t1 - t0) + "ms, dropdowns=" +
        document.querySelectorAll(".dropdown-menu").length);
    }
  }

  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function () {
      // Trailing debounce so a 200-node insert triggers one pass, not 200.
      setTimeout(function () {
        scheduled = false;
        timedScan();
      }, 50);
    });
  }

  new MutationObserver(schedule).observe(document.documentElement, {
    childList: true,
    subtree: true
  });

  /**
   * Forgets which elements have been promo-checked.
   *
   * Checking each element once is what made the scan cheap, but it means an
   * element that was static when first seen and is restyled to fixed later would
   * be skipped forever. Clearing on navigation bounds that: a promo shown for a
   * new page state gets one fresh look. The cost is a shallow query per
   * navigation, not per mutation, which is the distinction that mattered.
   */
  function resetPromoMarks() {
    var checked = document.querySelectorAll("[data-eksiengel-promo-checked]");
    for (var i = 0; i < checked.length; i++) {
      checked[i].removeAttribute("data-eksiengel-promo-checked");
    }
  }

  // pushState and replaceState mutate nothing, so the observer never sees
  // Ekşi's in-page navigation without this.
  ["pushState", "replaceState"].forEach(function (name) {
    var original = history[name];
    history[name] = function () {
      resetPromoMarks();
      var r = original.apply(this, arguments);
      schedule();
      return r;
    };
  });
  window.addEventListener("popstate", function () {
    resetPromoMarks();
    schedule();
  });

  // Host -> page.
  window.__eksiEngelOnMessage = function (raw) {
    var msg;
    try { msg = JSON.parse(raw); } catch (e) { return; }
    if (msg.type === "configChanged") {
      CONFIG = msg.payload || {};
      // Our own items come out first. Clearing the marks alone would rescan a
      // menu that still holds the previous labels, leaving "engelle" and
      // "sessize al" side by side.
      var mine = document.querySelectorAll("[" + ITEM_MARK + '="true"]');
      for (var p = 0; p < mine.length; p++) mine[p].remove();
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
