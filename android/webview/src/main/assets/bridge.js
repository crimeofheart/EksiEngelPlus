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
  /*
   * Ad slots only, and #aside is not one.
   *
   * It was added on the strength of a single 70px empty element seen while
   * chasing gaps that turned out to be in a different app entirely. On a profile
   * it is the sidebar -- entry counts, follower counts, join date, the follow
   * button -- and collapsing it while momentarily empty took all of that away.
   *
   * Every remaining selector is a container Ekşi itself marks as an ad.
   */
  var EMPTY_WHEN_BLOCKED = ".ads, .sticky-ad, .bottom-ads, .under-top-ad";

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

  /**
   * Horizontal swipe cycles the main tabs.
   *
   * The site's own tab strip is a row of ordinary links; this drives those same
   * links rather than inventing navigation, so whatever the site does on tap is
   * what a swipe does too.
   *
   * Anchored on the links present in the page instead of a fixed list of paths,
   * because the ones Ekşi serves differ between its layouts -- /basliklar/gundem
   * on one, /basliklar/m/populer on another.
   */
  var TAB_LABELS = ["bugün", "gündem", "debe", "takip"];

  function mainTabs() {
    var anchors = document.querySelectorAll("nav a[href], #top-navigation a[href], #sub-navigation a[href]");
    var found = [];
    var seen = {};
    for (var i = 0; i < anchors.length; i++) {
      var a = anchors[i];
      var label = (a.textContent || "").trim().toLowerCase();
      if (TAB_LABELS.indexOf(label) === -1) continue;
      if (seen[label]) continue;
      seen[label] = true;
      var marker = a.closest("li,div") || a;
      var active = /(^|\s)(active|selected|current)(\s|$)/.test(
        (a.className || "") + " " + (marker.className || "")
      );
      // Kept so the host can put its label under the tab it belongs to.
      found.push({ label: label, href: a.href, active: active, el: a });
    }
    // Keep the site's own order, not the order they happened to be found in.
    found.sort(function (x, y) {
      return TAB_LABELS.indexOf(x.label) - TAB_LABELS.indexOf(y.label);
    });
    return found;
  }

  /**
   * Which tab the current page belongs to.
   *
   * Not path equality: the site does not land on the path its own link points
   * at. The takip tab links to /basliklar/m/takip and arrives at
   * /basliklar/takipentrymobile, so an exact match found nothing and the swipe
   * refused to start there.
   *
   * The site's own active marker is tried first, since that is its answer rather
   * than our guess; a keyword in the path is the fallback.
   */
  var TAB_KEYWORDS = {
    "bugün": ["bugun"],
    "gündem": ["populer", "gundem"],
    "debe": ["debe"],
    "takip": ["takip"]
  };

  function currentTabIndex(tabs) {
    for (var i = 0; i < tabs.length; i++) {
      if (tabs[i].active) return i;
    }

    var here = location.pathname.toLowerCase();
    if (here === "/" || here === "") return 0;

    for (var j = 0; j < tabs.length; j++) {
      var words = TAB_KEYWORDS[tabs[j].label] || [];
      for (var k = 0; k < words.length; k++) {
        if (here.indexOf(words[k]) !== -1) return j;
      }
    }
    return -1;
  }

  /*
   * The tab the user came from, for pages that are not themselves a tab.
   *
   * A title opened out of gündem is at /slug--123: no active marker, no keyword
   * in the path, so currentTabIndex has nothing to go on and returns -1. That is
   * the right answer for "which tab is this page" and the wrong one for "which
   * tab is the user in", which is what the swipe needs on a single-page title.
   *
   * Per tab, not per session: sessionStorage is scoped to this WebView's session
   * already, and a value that outlived it would resume a cycle the user left
   * behind days ago.
   */
  var LAST_TAB_KEY = "eep.lastTab";

  function rememberTab(tabs, at) {
    if (at === -1) return;
    try { sessionStorage.setItem(LAST_TAB_KEY, tabs[at].label); } catch (e) {}
  }

  function rememberedTabIndex(tabs) {
    var label;
    try { label = sessionStorage.getItem(LAST_TAB_KEY); } catch (e) { return -1; }
    if (!label) return -1;
    for (var i = 0; i < tabs.length; i++) {
      if (tabs[i].label === label) return i;
    }
    return -1;
  }

  /**
   * A title's own pages, so a swipe inside an entry list turns the page.
   *
   * Only on a title -- /slug--123. The topic lists are paginated too, and keying
   * off "this page has a pager" would have replaced the tab cycle on gündem with
   * a page cycle, which is the one thing the swipe already did well.
   *
   * Returns null when the title has a single page, and the caller then falls
   * through to the tab ring: there is no page to turn to, so the gesture should
   * do what it does everywhere else rather than nothing.
   */
  function isTitlePage() {
    return /--\d+(\/|$)/.test(location.pathname);
  }

  function pageHref(page) {
    var url = new URL(location.href);
    // set, not append: ?a=dailynice and friends decide *which* entries are
    // paginated, so they have to survive the page change.
    url.searchParams.set("p", String(page));
    return url.href;
  }

  /**
   * How many pages the title has.
   *
   * data-pagecount is Ekşi's own answer and is taken when present. The link
   * sweep is for the layouts that render the pager without it: the highest p= it
   * offers is the last page, since the pager always links the end.
   */
  function pageCount(pager) {
    var stated = parseInt(pager.getAttribute("data-pagecount") || "", 10);
    if (stated > 0) return stated;

    var max = 0;
    var links = pager.querySelectorAll("a[href], option[value]");
    for (var i = 0; i < links.length; i++) {
      var raw = links[i].getAttribute("href") || links[i].getAttribute("value") || "";
      var found = /[?&]p=(\d+)/.exec(raw);
      // A bare option carries the number itself rather than a URL.
      var n = found ? parseInt(found[1], 10) : parseInt(raw, 10);
      if (n > max) max = n;
    }
    return max;
  }

  function currentPage(pager) {
    var stated = parseInt(pager.getAttribute("data-currentpage") || "", 10);
    if (stated > 0) return stated;
    var fromUrl = parseInt(new URL(location.href).searchParams.get("p") || "", 10);
    return fromUrl > 0 ? fromUrl : 1;
  }

  function pageItem(page) {
    return { label: pageLabel(page), href: pageHref(page) };
  }

  function titlePageRing() {
    if (!isTitlePage()) return null;
    var pager = document.querySelector(".pager");
    if (!pager) return null;

    var count = pageCount(pager);
    if (count < 2) return null;
    var page = Math.min(currentPage(pager), count);

    /*
     * Never the whole run: three entries, sometimes four.
     *
     * The ring is rebuilt on every drag and a long title has hundreds of pages,
     * so only the two the finger can reach are ever built.
     *
     * The ends are where the two rings meet. Off the last page the swipe carries
     * on into the next tab, and off the first back into the previous one, rather
     * than stopping dead at a boundary the user has no reason to know about: the
     * gesture means "keep going", and there is always somewhere to keep going to.
     * Not a wrap -- page one and the last page are genuinely the ends of *this*
     * title, and jumping between them would be a different answer entirely.
     */
    var tabs = tabRing();
    var items = [];

    if (page > 1) items.push(pageItem(page - 1));
    else if (tabs) pushIf(items, ringNeighbour(tabs, -1));

    var at = items.length;
    items.push(pageItem(page));

    if (page < count) items.push(pageItem(page + 1));
    else if (tabs) pushIf(items, ringNeighbour(tabs, 1));

    return { items: items, at: at, wrap: false };
  }

  function pushIf(items, entry) {
    if (entry) items.push(entry);
  }

  function pageLabel(page) {
    return "sayfa " + page;
  }

  function tabRing() {
    var tabs = mainTabs();
    if (tabs.length < 2) return null;
    var at = currentTabIndex(tabs);
    // On a title the page is not a tab, so where the user came from is the only
    // honest answer. Elsewhere -1 still means "unrelated page" and still refuses.
    if (at === -1 && isTitlePage()) at = rememberedTabIndex(tabs);
    if (at === -1) return null;
    return { items: tabs, at: at, wrap: true };
  }

  /** One step along a ring, or null where a non-wrapping one ends. */
  function ringNeighbour(ring, dir) {
    var n = ring.at + dir;
    if (n < 0) n = ring.wrap ? ring.items.length - 1 : -1;
    if (n >= ring.items.length) n = ring.wrap ? 0 : -1;
    return n === -1 ? null : ring.items[n];
  }

  /**
   * What this swipe cycles through, here.
   *
   * Pages when there are pages to turn, the main tabs otherwise. One shape for
   * both, so the drag, the preview and the commit never have to know which of
   * the two they are moving between -- which is also what lets the page ring
   * end in a tab without anything downstream noticing.
   */
  function swipeRing() {
    return titlePageRing() || tabRing();
  }

  function cycleTab(direction) {
    var tabs = mainTabs();
    if (tabs.length < 2) return;
    var at = currentTabIndex(tabs);
    // Not on a tab page: a swipe should not teleport somewhere unrelated.
    if (at === -1) return;
    var next = at + direction;
    if (next < 0) next = tabs.length - 1;
    if (next >= tabs.length) next = 0;
    location.href = tabs[next].href;
  }

  /**
   * Drag-to-switch, with the page following the finger.
   *
   * The outgoing page is the real DOM, translated; the incoming one is a preview
   * layer holding that tab's #content, fetched once and cached. A preview rather
   * than a live document because a tab page is a whole document -- running its
   * scripts in a layer beside the current one would be a second site inside the
   * first.
   *
   * Committing navigates for real, so what settles is always the site's own page
   * and never the preview.
   */
  var SWIPE = {
    surface: null,   // the element that moves
    layer: null,     // the incoming preview
    x0: 0, y0: 0, dx: 0,
    axis: null,      // null until the gesture commits to horizontal or vertical
    dir: 0,
    items: null,     // tabs, or this title's pages ending in its neighbouring tabs
    wrap: false,     // tabs cycle; a title's pages run out
    at: -1
  };

  var previewCache = {};

  /** The tab strip itself, so it can stay put while the pages move under it. */
  function tabStripEl() {
    var anchors = document.querySelectorAll("nav a[href], #top-navigation a[href], #sub-navigation a[href]");
    for (var i = 0; i < anchors.length; i++) {
      var label = (anchors[i].textContent || "").trim().toLowerCase();
      if (TAB_LABELS.indexOf(label) !== -1) {
        return anchors[i].closest("nav, ul") || anchors[i].parentElement;
      }
    }
    return null;
  }

  /**
   * The block that actually holds the page.
   *
   * Everything above it is chrome and must not move: the tab strip, and the
   * short bar Ekşi puts beneath it. That bar lives inside #content rather than
   * beside the strip, so transforming #content carried it along -- which is why
   * anchoring on the strip's siblings did nothing.
   *
   * The first tall child is the list; height is the test rather than a selector,
   * because the chrome differs between Ekşi's layouts and a list of ids would
   * rot.
   */
  var CHROME_BAR_MAX_HEIGHT = 90;

  function pagerEl() {
    var content = document.getElementById("content") || document.body;
    var kids = content.children;
    for (var i = 0; i < kids.length; i++) {
      if (kids[i].getBoundingClientRect().height > CHROME_BAR_MAX_HEIGHT) return kids[i];
    }
    return content;
  }

  /** Viewport y where the pages begin: the top of that block. */
  function pagerTop() {
    var t = pagerEl().getBoundingClientRect().top;
    return t > 0 && t < (window.innerHeight || 0) ? Math.round(t) : 0;
  }

  /** What slides. The chrome above it stays anchored. */
  function surfaceEl() {
    return pagerEl();
  }

  /** [dir] is needed up front: a ring with no neighbour that way is no drag. */
  /** [dir] is needed up front: a ring with no neighbour that way is no drag. */
  function beginDrag(dir) {
    var ring = swipeRing();
    if (!ring) return false;
    SWIPE.items = ring.items;
    SWIPE.at = ring.at;
    SWIPE.wrap = ring.wrap;
    if (!neighbour(dir)) return false;
    SWIPE.surface = surfaceEl();
    SWIPE.surface.style.willChange = "transform";
    return true;
  }

  /** SWIPE carries items/at/wrap, so it is a ring like any other. */
  function neighbour(dir) {
    return ringNeighbour(SWIPE, dir);
  }

  /*
   * The panel the incoming page is drawn on.
   *
   * Created once and reused: a fresh element per drag would restart the
   * compositor layer mid-gesture, which is visible as a stutter on the first
   * frame of every swipe.
   */
  function ensureLayer() {
    if (SWIPE.layer && SWIPE.layer.isConnected) return SWIPE.layer;
    var layer = document.createElement("div");
    layer.setAttribute("data-eep-preview", "1");
    layer.style.cssText = [
      "position:fixed",
      "left:0",
      "right:0",
      "bottom:0",
      "z-index:2147483000",
      "display:none",
      "will-change:transform",
      "pointer-events:none",
    ].join(";");
    document.body.appendChild(layer);
    SWIPE.layer = layer;
    return layer;
  }

  /*
   * Fetches a tab's markup into previewCache.
   *
   * XHR-flagged so Ekşi returns the partial rather than a full document -- the
   * same header the entry lists use. Failures are swallowed: a warm cache is an
   * optimisation, and the drag falls back to showing the tab's label.
   */
  function preloadTab(href) {
    if (!href || previewCache[href] || preloadTab.inFlight[href]) return;
    preloadTab.inFlight[href] = true;
    fetch(href, {
      credentials: "include",
      headers: { "x-requested-with": "XMLHttpRequest" },
    })
      .then(function (r) { return r.ok ? r.text() : null; })
      .then(function (html) {
        if (html) previewCache[href] = html;
      })
      .catch(function () {})
      .then(function () { delete preloadTab.inFlight[href]; });
  }
  preloadTab.inFlight = {};

  /**
   * Warms both neighbours, so the first drag of a session has content.
   *
   * Also the one place the current tab is recorded, for the titles opened out of
   * it: it already resolves the ring, and it runs on every page.
   */
  function warmNeighbours() {
    var tabs = mainTabs();
    rememberTab(tabs, currentTabIndex(tabs));

    var ring = swipeRing();
    if (!ring) return;
    for (var d = -1; d <= 1; d += 2) {
      var side = ringNeighbour(ring, d);
      if (side) preloadTab(side.href);
    }
  }

  function renderPreview(layer, tab) {
    var cached = previewCache[tab.href];
    layer.innerHTML =
      '<div style="padding:16px;opacity:0.98;height:100%;overflow:hidden">' +
      (cached || '<div style="padding:24px;font-size:15px;opacity:0.7">' + tab.label + "</div>") +
      "</div>";
  }

  function showPreview(dir) {
    var tab = neighbour(dir);
    var layer = ensureLayer();
    renderPreview(layer, tab);
    // Start below the tabs, so the incoming page appears from under the strip
    // rather than sliding over it.
    layer.style.top = pagerTop() + "px";
    layer.style.background = getComputedStyle(document.body).backgroundColor || "#fff";
    layer.style.display = "block";

    /*
     * Not fetched yet: show the label now and fill the page in when it arrives.
     *
     * Warming happens after load, but a drag can still beat it on a slow
     * connection, and a blank panel says nothing about where the user is going,
     * which is the whole reason the preview exists.
     */
    if (!previewCache[tab.href]) {
      preloadTab(tab.href);
      var tries = 0;
      var poll = setInterval(function () {
        tries++;
        if (previewCache[tab.href]) {
          if (SWIPE.layer === layer && layer.style.display === "block") renderPreview(layer, tab);
          clearInterval(poll);
        } else if (tries > 40) {
          clearInterval(poll);
        }
      }, 100);
    }
    return layer;
  }

  function move(dx, dir) {
    var w = window.innerWidth || 1;
    SWIPE.surface.style.transition = "none";
    SWIPE.surface.style.transform = "translateX(" + dx + "px)";
    if (SWIPE.layer) {
      SWIPE.layer.style.transition = "none";
      // The incoming page sits just off the edge the finger is pulling from.
      var from = dir > 0 ? w : -w;
      SWIPE.layer.style.transform = "translateX(" + (from + dx) + "px)";
    }
  }

  function settle(commit, dir) {
    var w = window.innerWidth || 1;
    var ease = "transform 180ms ease-out";
    SWIPE.surface.style.transition = ease;
    if (SWIPE.layer) SWIPE.layer.style.transition = ease;

    if (commit) {
      SWIPE.surface.style.transform = "translateX(" + (dir > 0 ? -w : w) + "px)";
      if (SWIPE.layer) SWIPE.layer.style.transform = "translateX(0px)";
      var target = neighbour(dir);
      // Tell the host where we are going. A navigation tears this document down,
      // so nothing here can paint during the load -- only a native view can, and
      // the alternative is the blank frame the user sees instead.
      // Same cutoff the drag uses, in device pixels, so the host's cover starts
      // exactly where the page does and the chrome above it stays visible.
      var dpr = window.devicePixelRatio || 1;
      var tabLeft = target.el ? target.el.getBoundingClientRect().left : 0;
      send("navigating", {
        label: target.label,
        top: Math.round(pagerTop() * dpr),
        left: Math.round(tabLeft * dpr)
      });
      setTimeout(function () { location.href = target.href; }, 170);
      return;
    }

    SWIPE.surface.style.transform = "translateX(0px)";
    if (SWIPE.layer) SWIPE.layer.style.transform = "translateX(" + (dir > 0 ? w : -w) + "px)";
    setTimeout(function () {
      if (SWIPE.layer) { SWIPE.layer.style.display = "none"; SWIPE.layer.innerHTML = ""; }
      SWIPE.surface.style.transition = "";
      SWIPE.surface.style.transform = "";
      SWIPE.surface.style.willChange = "";
    }, 200);
  }

  (function installSwipe() {
    var MIN_X = 12;        // before this the gesture has not chosen an axis
    var MAX_Y = 24;        // past this vertically it is a scroll, not a swipe
    var COMMIT = 0.28;     // fraction of the screen that counts as "go"

    document.addEventListener("touchstart", function (e) {
      SWIPE.axis = null;
      SWIPE.dx = 0;
      if (e.touches.length !== 1) return;
      SWIPE.x0 = e.touches[0].clientX;
      SWIPE.y0 = e.touches[0].clientY;
      // Warm the neighbours so the first drag has something to show.
      warmNeighbours();
    }, { passive: true });

    document.addEventListener("touchmove", function (e) {
      if (e.touches.length !== 1) return;
      var dx = e.touches[0].clientX - SWIPE.x0;
      var dy = e.touches[0].clientY - SWIPE.y0;

      if (SWIPE.axis === null) {
        if (Math.abs(dy) > MAX_Y) { SWIPE.axis = "y"; return; }
        if (Math.abs(dx) < MIN_X) return;
        SWIPE.axis = "x";
        // Direction first: beginDrag has to know which way to look before it can
        // say whether there is anywhere to go. On the last page of a title there
        // is no next, and the drag should not start at all rather than start and
        // then have nothing to settle onto.
        var dir = dx < 0 ? 1 : -1;
        if (!beginDrag(dir)) { SWIPE.axis = "y"; return; }
        SWIPE.dir = dir;
        showPreview(dir);
      }
      if (SWIPE.axis !== "x") return;

      // Only now, once the gesture is definitely horizontal, is it ours to keep.
      if (e.cancelable) e.preventDefault();
      SWIPE.dx = dx;
      move(dx, SWIPE.dir);
    }, { passive: false });

    document.addEventListener("touchend", function () {
      if (SWIPE.axis !== "x" || !SWIPE.surface) { SWIPE.axis = null; return; }
      SWIPE.axis = null;
      var w = window.innerWidth || 1;
      var far = Math.abs(SWIPE.dx) > w * COMMIT;
      var sameWay = (SWIPE.dx < 0 ? 1 : -1) === SWIPE.dir;
      settle(far && sameWay, SWIPE.dir);
    }, { passive: true });

    /*
     * The drag taken away from us mid-gesture.
     *
     * A native view that intercepts -- the pull-to-refresh above this WebView is
     * the one that can -- gets the rest of the touch stream, and all the page
     * receives is this. Without it there is no touchend, settle() is never
     * called, and the surface stays translated at whatever offset the finger
     * reached: a page left sitting half off screen with no way back.
     *
     * Always back to origin, never committed. The gesture did not finish, so
     * there is no distance at which it counts as a decision.
     */
    document.addEventListener("touchcancel", function () {
      if (SWIPE.axis !== "x" || !SWIPE.surface) { SWIPE.axis = null; return; }
      SWIPE.axis = null;
      settle(false, SWIPE.dir);
    }, { passive: true });
  })();

  function item(label, compact) {
    var li = document.createElement("li");
    li.setAttribute(ITEM_MARK, "true");
    // Title items sit inside the site's own menu, which is sized for its own
    // labels; ours are longer, so they get tighter metrics rather than wrapping
    // and pushing the menu taller than the screen.
    if (compact) li.style.cssText = "white-space:nowrap;font-size:12px;line-height:1.5";
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

  /**
   * The row of controls under a title: şükela, başlıkta ara, takip et,
   * başlığı açan.
   *
   * Not a dropdown, which is what three attempts at this got wrong. It is
   * .sub-title-menu, a row of plain anchors, and most of its items are added by
   * the site's own script after load -- only şükela is in the served HTML.
   */
  function titleRowAnchor(container) {
    var kids = container.children;
    for (var i = 0; i < kids.length; i++) {
      if ((kids[i].textContent || "").toLowerCase().indexOf("başlığı açan") !== -1) return kids[i];
    }
    return null;
  }

  /**
   * One entry in the row that opens the pair, rather than two entries.
   *
   * Mirrors the shape the site uses beside it -- a wrapper holding a toggle
   * anchor and a list -- so it sits in the row as one item instead of widening
   * it with two long labels.
   */
  function titleSubmenu(label, options) {
    var wrap = document.createElement("div");
    wrap.setAttribute(ITEM_MARK, "true");
    wrap.style.cssText = "display:inline-block;position:relative";

    var toggle = document.createElement("a");
    toggle.className = "expandable";
    toggle.style.cssText = "cursor:pointer;margin-left:8px;white-space:nowrap";
    toggle.textContent = label;

    var list = document.createElement("ul");
    list.style.cssText = [
      "display:none", "position:absolute", "right:0", "top:100%", "z-index:100",
      "margin:4px 0 0", "padding:6px 0", "list-style:none", "min-width:210px",
      "background:#fff", "color:#333", "border-radius:4px",
      "box-shadow:0 2px 10px rgba(0,0,0,0.25)"
    ].join(";");

    options.forEach(function (opt) {
      var li = document.createElement("li");
      var a = document.createElement("a");
      a.textContent = opt.label;
      a.style.cssText = "display:block;padding:7px 14px;white-space:nowrap;cursor:pointer";
      a.onclick = function () {
        list.style.display = "none";
        opt.run();
      };
      li.appendChild(a);
      list.appendChild(li);
    });

    toggle.onclick = function () {
      list.style.display = list.style.display === "block" ? "none" : "block";
    };
    // Any tap elsewhere closes it, the way the site's own menus behave.
    document.addEventListener("click", function (e) {
      if (!wrap.contains(e.target)) list.style.display = "none";
    });

    wrap.appendChild(toggle);
    wrap.appendChild(list);
    return wrap;
  }

  function injectTitleMenu(container) {
    // Topics only. A profile carries both a #title and a sub-title row of its
    // own, so keying off those alone put "başlıktakileri engelle" under a user's
    // block menu, where it means nothing.
    if (location.pathname.indexOf("/biri/") === 0) return;

    var title = document.getElementById("title");
    if (!title) return false;
    var slug = title.getAttribute("data-slug");
    var id = title.getAttribute("data-id");
    if (!slug || !id) return false;

    function enqueueTitle(spec) {
      enqueue({
        banSource: BanSource.TITLE, banMode: BanMode.BAN,
        targetType: CONFIG.enableMute ? TargetType.MUTE : TargetType.USER,
        clickSource: ClickSource.TITLE,
        titleName: slug, titleId: Number(id),
        timeSpecifier: spec
      });
    }

    var menu = titleSubmenu(muteWord("engelle", "sessize al"), [
      {
        label: muteWord("başlıktakileri engelle (son 24 saat)", "başlıktakileri sessize al (son 24 saat)"),
        run: function () { enqueueTitle(TimeSpecifier.LAST_24_H); }
      },
      {
        label: muteWord("başlıktakileri engelle (tümü)", "başlıktakileri sessize al (tümü)"),
        run: function () { enqueueTitle(TimeSpecifier.ALL); }
      }
    ]);

    var anchor = titleRowAnchor(container);
    if (anchor) container.insertBefore(menu, anchor);
    else container.appendChild(menu);
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
    var banFav = item(muteWord("favlayanları engelle", "favlayanları sessize al"));
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
        // The author rides along even though the run targets the favouriters:
        // it is what names the operation on the status screen, and without it
        // three queued "favlayanlar" rows are indistinguishable.
        banSource: BanSource.FAV, banMode: BanMode.BAN, targetType: targetType,
        clickSource: clickSource, authorName: authorName,
        entryUrl: entryUrl, entryId: Number(entryId)
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
    // Not mute-aware on purpose: title blocking is its own relation (r=i) with
    // no mute counterpart, so this action does the same thing either way.
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
    { selector: ".sub-title-menu", apply: injectTitleMenu },
    { selector: ".dropdown-menu", apply: injectEntryMenu },
    { selector: ".dropdown-menu", apply: injectShareMenu },
    { selector: ".profile-buttons", apply: injectProfile }
  ];

  /** Applies the already-handled guard to each part of a selector list. */
  function markedSelector(selector, mark) {
    return selector
      .split(",")
      .map(function (part) { return part.trim() + ":not([" + mark + '="true"])'; })
      .join(",");
  }

  function scan() {
    for (var i = 0; i < injectors.length; i++) {
      var inj = injectors[i];
      // Per-injector mark: two injectors share the .dropdown-menu selector, so a
      // single shared mark would let whichever ran first consume the node.
      var mark = MARK + "-" + i;
      /*
       * The guard has to be attached to every part of the selector.
       *
       * "a, b" + ":not([mark])" parses as "a" and "b:not([mark])" -- the first
       * part keeps matching however many times it has already been handled. That
       * turned one injector into an endless loop: inject, mutate, rescan,
       * inject, with the menu growing by two items every frame.
       */
      var nodes = document.querySelectorAll(markedSelector(inj.selector, mark));
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
  // After first paint, so warming never competes with the page coming up.
  setTimeout(warmNeighbours, 1500);
})();
