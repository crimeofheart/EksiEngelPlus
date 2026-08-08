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
    tabs: null,
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

  function beginDrag() {
    SWIPE.tabs = mainTabs();
    SWIPE.at = currentTabIndex(SWIPE.tabs);
    if (SWIPE.at === -1 || SWIPE.tabs.length < 2) return false;
    SWIPE.surface = surfaceEl();
    SWIPE.surface.style.willChange = "transform";
    return true;
  }

  function neighbour(dir) {
    var n = SWIPE.at + dir;
    if (n < 0) n = SWIPE.tabs.length - 1;
    if (n >= SWIPE.tabs.length) n = 0;
    return SWIPE.tabs[n];
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
      var tabs = mainTabs();
      var at = currentTabIndex(tabs);
      if (at !== -1 && tabs.length > 1) {
        preloadTab(tabs[(at + 1) % tabs.length].href);
        preloadTab(tabs[(at - 1 + tabs.length) % tabs.length].href);
      }
    }, { passive: true });

    document.addEventListener("touchmove", function (e) {
      if (e.touches.length !== 1) return;
      var dx = e.touches[0].clientX - SWIPE.x0;
      var dy = e.touches[0].clientY - SWIPE.y0;

      if (SWIPE.axis === null) {
        if (Math.abs(dy) > MAX_Y) { SWIPE.axis = "y"; return; }
        if (Math.abs(dx) < MIN_X) return;
        SWIPE.axis = "x";
        if (!beginDrag()) { SWIPE.axis = "y"; return; }
        SWIPE.dir = dx < 0 ? 1 : -1;
        showPreview(SWIPE.dir);
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
   * The title's own dropdown, identified by an item only it carries.
   *
   * "başlığı açan" appears in the title menu and nowhere else, which is what
   * separates it from the per-entry menus that share the .dropdown-menu class.
   */
  function isTitleMenu(menu) {
    return (menu.textContent || "").toLowerCase().indexOf("başlığı açan") !== -1;
  }

  /** The item our two sit above, so they land inside the existing group. */
  function titleAnchorItem(menu) {
    var items = menu.querySelectorAll("li");
    for (var i = 0; i < items.length; i++) {
      if ((items[i].textContent || "").toLowerCase().indexOf("başlığı açan") !== -1) return items[i];
    }
    return null;
  }

  /**
   * Title actions, mirroring script.js:184-265.
   *
   * Placed in the site's own menu between "takip et" and "başlığı açan" rather
   * than in a strip of their own: they are title actions, and the menu is where
   * a user already looks for those.
   */
  function injectTitleMenu(menu) {
    if (!isTitleMenu(menu)) return;
    var title = document.getElementById("title");
    if (!title) return false;
    var slug = title.getAttribute("data-slug");
    var id = title.getAttribute("data-id");
    if (!slug || !id) return;

    var last24 = item(muteWord("başlıktakileri engelle (24s)", "başlıktakileri sessize al (24s)"), true);
    var all = item(muteWord("başlıktakileri engelle (tümü)", "başlıktakileri sessize al (tümü)"), true);

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

    var anchor = titleAnchorItem(menu);
    if (anchor) {
      menu.insertBefore(last24, anchor);
      menu.insertBefore(all, anchor);
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
    { selector: ".dropdown-menu", apply: injectTitleMenu },
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
  // After first paint, so warming never competes with the page coming up.
  setTimeout(warmNeighbours, 1500);
})();
