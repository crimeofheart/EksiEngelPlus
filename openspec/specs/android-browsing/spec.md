# android-browsing Specification

## Purpose
TBD - created by archiving change android-browsing-shell. Update Purpose after archive.
## Requirements
### Requirement: The WebView shows the real site, not a reimplementation

Browsing SHALL be a WebView loading eksisozluk.com. The app SHALL NOT render Ekşi
content natively.

Verified during `android-spike`: the site renders fully in an Android WebView —
gündem, entries, authors, navigation — with no interstitial and no degraded
fallback. Reimplementing it would mean maintaining a full third-party client
whose every page breaks when the site changes, rather than only the selectors we
inject against.

The WebView SHALL send the same user agent the OkHttp stack sends, so a session
established here is usable there.

#### Scenario: Site renders

- **WHEN** the browser screen opens
- **THEN** eksisozluk.com loads and behaves as it does in a mobile browser

#### Scenario: Only Ekşi loads in the WebView

- **WHEN** a link to another host is tapped
- **THEN** it opens outside the WebView, so the bridge's origin allowlist stays meaningful

### Requirement: Login happens in the WebView and nowhere else

The app SHALL offer login only by loading `/giris` in the WebView, and SHALL NOT
present a native credential form.

`/giris` is protected by Cloudflare Turnstile, which requires a real browser
executing JavaScript. A native form cannot satisfy it — measured in the spike,
where a scripted POST with valid credentials and a valid antiforgery token was
rejected with `doğrulama başarısız`.

#### Scenario: Login succeeds

- **WHEN** the user completes the form in the WebView, solving Turnstile
- **THEN** session cookies land in `CookieManager` and the engine's requests are authenticated

#### Scenario: No credential prompt

- **WHEN** the app needs a session
- **THEN** it routes the user to the WebView rather than asking for their password

### Requirement: Scripts are injected before the page runs

Injection SHALL use `WebViewCompat.addDocumentStartJavaScript` with an origin
allowlist, falling back to `onPageCommitVisible` where unsupported.

`DOCUMENT_START_SCRIPT` was confirmed available on WebView 124 and 149 during the
spike. Document-start injection means the menu is present before the page paints,
rather than appearing a beat later; the fallback is guarded by an idempotence
flag so a page cannot be augmented twice.

#### Scenario: Menu present on first paint

- **WHEN** an entry page finishes loading
- **THEN** the injected items are already in the dropdown

#### Scenario: Double injection is impossible

- **WHEN** both the document-start script and the fallback run
- **THEN** exactly one set of items is injected

### Requirement: Injection survives in-page navigation

A single persistent `MutationObserver` SHALL re-apply injectors as the DOM
changes, and SHALL additionally rescan on `pushState`, `replaceState` and
`popstate`.

`waitForElm` (`script.js:75-105`) resolves once and calls `observer.disconnect()`.
That is correct for one page load and wrong for Ekşi's XHR-driven pagination —
later-injected DOM never receives menu items. In a WebView, where the user never
reloads because there is no address bar, the defect is far more visible than in
the extension.

History methods produce no DOM mutation of their own, so they must be hooked
separately.

#### Scenario: Paging within a title

- **WHEN** the user pages through a title without a full reload
- **THEN** newly rendered entries receive menu items

#### Scenario: History navigation

- **WHEN** navigation happens via `pushState` or the back button
- **THEN** a rescan runs even though no mutation fired

### Requirement: An observer pass costs work proportional to what changed

A scan SHALL examine only nodes it has not already examined. No scan SHALL walk
the whole document, and no per-element style resolution SHALL be repeated for an
element already seen.

The observer fires on every XHR page append, so any per-scan work proportional to
document size is quadratic in the length of the page. Follower and following
lists are the worst case in the app — several hundred rows, extended as the user
scrolls — and this is where the cost became visible as pages that loaded far
slower than the same pages in a plain browser.

The promo fallback is the specific hazard: it calls `getComputedStyle` per
candidate, which forces a style resolution. Candidates SHALL therefore be limited
to elements shallow enough to be an overlay, and SHALL be marked as examined
before any style is read, not after a filter has already rejected them.

Marks SHALL be cleared on navigation, so an element restyled for a new page state
gets one fresh look without reintroducing per-mutation cost.

#### Scenario: Appending a page of rows

- **WHEN** a page of results is appended to a list of several hundred rows
- **THEN** the resulting scan resolves style for the new nodes only, and not for the rows already present

#### Scenario: Repeated pagination does not compound

- **WHEN** the user pages five times through a long list
- **THEN** total style resolutions grow with the rows added, not with rows already on the page multiplied by the number of scans

### Requirement: Injected nodes are marked with a lowercase attribute

Processed nodes SHALL be marked with `data-eksiengel-processed`, and guards SHALL
test that exact name.

The extension writes the mark via `dataset.eksiengelProcessed`, which produces
`data-eksiengel-processed`, but guards against
`:not([data-eksiengelProcessed="true"])`. HTML attribute names are lowercased by
the parser, so that selector never matches and de-duplication rests entirely on a
separate early-return check. The port fixes the casing so the guard does its job.

#### Scenario: A processed node is skipped

- **WHEN** the observer revisits a node already carrying the mark
- **THEN** the selector excludes it, without relying on a secondary check

### Requirement: The same items are injected into the same targets

The bridge SHALL inject, matching `script.js`:

| Target | Items |
| --- | --- |
| `#in-topic-search-options` | başlıktakileri engelle — son 24 saatte, tümü |
| entry dropdown | yazarı engelle/sessize al, favlayanları engelle, takipçilerini engelle |
| `.profile-buttons` | engelle/sessize al *or* engellemeyi bırak, başlıklarını engelle *or* başlıkları engellemeyi kaldır, takipçilerini engelle |
| `#user-notifications` | a transient confirmation toast |

The entry menu SHALL be identified by its contents, not its position: four
`.dropdown-menu` elements render per page, so the extension's text match against
`['engelle','modlog','şikayet','mesaj']` (`script.js:315`) is required rather than
defensive.

`ul.toggles-menu` SHALL NOT be relied on. It matches zero elements on every page
type, logged in and out.

Labels SHALL follow config, showing mute wording when `enableMute` is set.

On a profile, the two items that stand for a relation SHALL take their direction
from the relation's current state rather than always offering to add it. The
state is carried by the `.relation-link` elements the injector already selects:
`data-add-caption` names the relation and `data-added` is `"true"` when it is
already in place (`script.js:475-516`).

| `data-add-caption` | `data-added="true"` | otherwise |
| --- | --- | --- |
| `engelle` | "engellemeyi bırak", `banMode` UNDOBAN, `targetType` USER | "engelle"/"sessize al", `banMode` BAN, `targetType` per `enableMute` |
| `başlıklarını engelle` | "başlıkları engellemeyi kaldır", `banMode` UNDOBAN, `targetType` TITLE | "başlıklarını engelle", `banMode` BAN, `targetType` TITLE |

Undoing a block SHALL use `targetType` USER even when `enableMute` is set. The
relation being removed is the one Ekşi recorded, and `data-add-caption="engelle"`
is the block relation (`r=m`) whether or not this client prefers to mute.

"takipçilerini engelle" SHALL remain BAN-only. It is not a relation on the
profile being viewed but an operation over that user's follower list, so no
`.relation-link` carries its state and there is nothing to invert.

An item SHALL NOT be injected for a relation whose `.relation-link` is absent.
Ekşi renders no link for the mute relation, so its state cannot be read from the
page; offering an unconditional "sessizden çıkar" would be a control that does
nothing whenever the user was not muted.

Ekşi's own `#button-blocked-link` SHALL continue to be removed, so there is one
control rather than two (`script.js:489`). This is conditional on the injected
item covering both directions: while it offered only BAN, removing the native
button took away the only working undo on the page.

#### Scenario: Entry menu identified among four dropdowns

- **WHEN** an entry page is processed
- **THEN** the menu containing the expected items receives the injection and the other three do not

#### Scenario: Labels follow settings

- **WHEN** `enableMute` is enabled
- **THEN** items read "sessize al" instead of "engelle"

#### Scenario: An already-blocked user is offered the undo

- **WHEN** a profile is injected and its `engelle` relation link carries `data-added="true"`
- **THEN** the item reads "engellemeyi bırak" and enqueues `banMode` UNDOBAN with `targetType` USER

#### Scenario: A user who is not blocked is offered the block

- **WHEN** a profile is injected and its `engelle` relation link does not carry `data-added="true"`
- **THEN** the item reads "engelle" — or "sessize al" under `enableMute` — and enqueues `banMode` BAN

#### Scenario: Title blocking inverts independently of user blocking

- **WHEN** a profile has `engelle` unset and `başlıklarını engelle` set to `data-added="true"`
- **THEN** the first item offers the block and the second offers "başlıkları engellemeyi kaldır" with `banMode` UNDOBAN and `targetType` TITLE

#### Scenario: Undoing a block is not redirected to the mute relation

- **WHEN** `enableMute` is set and an already-blocked user's profile is injected
- **THEN** the undo item enqueues `targetType` USER, because that is the relation Ekşi holds

#### Scenario: Blocking followers is never inverted

- **WHEN** any profile is injected
- **THEN** "takipçilerini engelle" enqueues `banMode` BAN regardless of every `data-added` on the page

### Requirement: The page talks to the app over an origin-scoped channel

JS→Kotlin messaging SHALL use `WebViewCompat.addWebMessageListener` with an
origin allowlist, carrying a versioned JSON envelope.

It SHALL NOT use `addJavascriptInterface`, which exposes the object to every page
the WebView loads with no origin scoping — unacceptable when the WebView browses
a user-content site full of arbitrary links.

The enqueue payload SHALL carry the same fields `EksiEngel_sendMessage` sends
(`script.js:30-45`), so the bridge boundary and the operation request share one
shape.

#### Scenario: Tapping an item starts an operation

- **WHEN** the user taps an injected item
- **THEN** an envelope reaches Kotlin and an operation is enqueued

#### Scenario: Other origins cannot reach the bridge

- **WHEN** a page outside the allowlist is loaded
- **THEN** the messaging object is absent

### Requirement: Config reaches the page without an async race

Config SHALL be serialised into the document-start preamble so the page reads it
synchronously, and changes SHALL be pushed to already-loaded pages.

The extension reads config asynchronously (`script.js:7-28`) and can inject a
label before the value arrives. Baking it into the preamble removes the race.

A change SHALL be applied twice over: pushed to the open page, and folded into the
document-start script so the next document reads the new values. The push alone
would leave the next navigation stale; the re-registration alone would leave the
page in front of the user stale until they navigated.

Re-rendering SHALL remove the items already injected before rescanning. Clearing
the processed marks alone appends a second set, leaving "engelle" and "sessize al"
in the same menu.

#### Scenario: Labels are correct on first paint

- **WHEN** a page loads with `enableMute` set
- **THEN** the first render of the menu already shows mute wording

#### Scenario: A settings change reaches an open page

- **WHEN** config changes while a page is open
- **THEN** the page is notified and re-renders its labels

#### Scenario: A settings change survives the next navigation

- **WHEN** a page is loaded after config changed
- **THEN** it reads the new values from the preamble, without a second push

#### Scenario: Re-rendering replaces rather than accumulates

- **WHEN** labels are re-rendered after a config change
- **THEN** exactly one set of injected items remains

### Requirement: Assets are embedded, not served

The injected icon SHALL be a `data:` URI compiled into the preamble.

It is the only thing `web_accessible_resources` was providing, and that mechanism
exposed thirty files — including the API key — to every website. There is no
Android equivalent and none SHALL be recreated.

#### Scenario: Icon renders with no host

- **WHEN** an injected item is displayed
- **THEN** its icon renders without any request leaving the page

### Requirement: Login state is observable

A `SessionMonitor` SHALL expose login state as observable state, refreshed when
the WebView navigates to `/`, `/giris` or `/cikis`.

Presence of a session SHALL be determined authoritatively by scraping the
homepage avatar; a cookie check MAY be used as a fast negative but never as a
positive.

An operation in `PAUSED_AUTH` SHALL become resumable when a session is observed.
It SHALL be offered, never resumed automatically: a login is not consent to
restart a run the user may have abandoned deliberately.

Resumption SHALL NOT depend on the caller still holding the original request. The
request SHALL be persisted alongside the checkpoint, because `WorkInfo` does not
expose a worker's input data once it has returned, and the screen making the offer
is typically a different one, hours later.

#### Scenario: Logging in resumes a parked operation

- **WHEN** the user logs in while an operation waits in `PAUSED_AUTH`
- **THEN** the operation is offered for resumption

#### Scenario: The offer is not a restart

- **WHEN** a session appears and the offer is not taken
- **THEN** the operation stays parked

#### Scenario: Resumption outlives the screen that started the run

- **WHEN** the process has died since the operation was queued
- **THEN** the offer still carries enough to restart it, from its stored cursor

#### Scenario: Cookie presence is not proof

- **WHEN** an auth cookie exists but the homepage yields no nick
- **THEN** the state is logged out

### Requirement: Ekşi links never leave the app

Any navigation to an Ekşi host SHALL stay inside the WebView. Only genuinely
external hosts SHALL be handed to the system.

Handing an Ekşi URL out is worse than it sounds: the system browser opens, then
Android app-link handling forwards it to the *official* Ekşi app, so a tap inside
our client silently ends up in a competitor's.

The family is wider than the dictionary — `eksiup` hosts images, `eksiseyler` is
the content arm — so a host SHALL be treated as Ekşi when any dot-separated
**label** begins with `eksi`, plus the configured base and known mirrors.

A bare substring test SHALL NOT be used: it would capture unrelated hosts, most
obviously anything containing `meksika`. The label rule still admits something
like `eksik.com`, which is the right trade — the cost is one page rendering
in-app rather than in the browser, against the cost of silently handing users to
a competing client.

Links opened with `target="_blank"` or `window.open` SHALL also stay in the
WebView.

#### Scenario: A mirror domain stays in the app

- **WHEN** a link points at an Ekşi mirror or subdomain not literally listed
- **THEN** it loads in the WebView rather than being handed to the system

#### Scenario: Sibling Ekşi properties stay in the app

- **WHEN** a link points at `eksiup.com`, `img.eksiup.com` or `eksiseyler.com`
- **THEN** it loads in the WebView

#### Scenario: Their shortener stays in the app

- **WHEN** an entry embeds a `soz.lk` link, as image posts do
- **THEN** it loads in the WebView and the redirect resolves there

A shortener defeats host matching by design — the destination is only knowable
after the redirect — so Ekşi-owned hosts carrying no `eksi` in their name SHALL be
named explicitly. Other shorteners SHALL NOT be assumed to be theirs.

#### Scenario: A lookalike host is not captured

- **WHEN** a link points at a host merely containing `eksi` inside another word, such as `meksika-haber.com`
- **THEN** it is treated as external and opens outside the WebView

#### Scenario: A genuinely external link leaves

- **WHEN** a link points at an unrelated host
- **THEN** it opens outside the WebView

#### Scenario: A new-window link does not escape

- **WHEN** a link declares `target="_blank"`
- **THEN** it loads in the same WebView

#### Scenario: An app-open intent is swallowed

- **WHEN** the page navigates to an `intent://` URL naming the official Ekşi app, or to an `eksi*` custom scheme
- **THEN** the navigation is discarded and, where the intent carries a `browser_fallback_url` on an Ekşi host, that URL loads in the WebView instead

#### Scenario: Unrelated schemes still reach the system

- **WHEN** the page navigates to `mailto:` or `tel:`
- **THEN** it is handed to the system, because the app genuinely cannot handle those

### Requirement: The app can open Ekşi links from elsewhere

The browsing activity SHALL accept `VIEW` intents for Ekşi URLs so other apps can
open a link here.

The filter SHALL NOT use `autoVerify`: claiming every Ekşi link would decide for
the user rather than offering a choice.

#### Scenario: Opening a shared link

- **WHEN** an Ekşi URL is opened from another app and this one is chosen
- **THEN** that URL loads in the WebView rather than the homepage

### Requirement: Confirmation is unobtrusive

The confirmation shown after queuing an operation SHALL be a small transient
overlay of our own, not the site's notification component.

Reusing `#user-notifications` inherits Ekşi's mobile styling, which renders at
full width with a large call-to-action and dominates the screen for a message
that only needs to say "queued".

#### Scenario: Queuing shows a compact confirmation

- **WHEN** an operation is queued
- **THEN** a small toast appears briefly and disappears on its own, without covering page content

### Requirement: Entries can be shared through the system sheet

The share menu of an entry SHALL carry a plain "paylaş" item, placed above the
site's own per-network options, which opens the Android share sheet with the
entry's URL.

The site offers only per-network destinations. The system sheet covers everything
the user actually has installed, and is the interaction an Android user expects.

#### Scenario: Sharing an entry

- **WHEN** the user taps the injected "paylaş" item
- **THEN** the Android share sheet opens carrying that entry's URL

#### Scenario: Placement

- **WHEN** the share menu is injected
- **THEN** the item appears before the site's own network-specific entries

### Requirement: Titles are acted on by holding them

Holding a title link SHALL open a menu offering, in the app's accent green:
copying the title's name, copying its address, and sharing its address through
the Android share sheet. Nothing SHALL be rendered for a title until it is held.

A title has no container to inject into: `.sub-title-menu` is a row of the site's
own anchors that the block submenu already occupies, and a list page carries no
per-title element at all. A visible control would therefore appear on every line
of a page that is nothing but title links.

A title SHALL be recognised by its address — a path of the form `/slug--1234567`
— rather than by the page or the container it appears in, so that the gesture
works wherever Ekşi renders a title link. The header of the title being read
SHALL also be recognised, by position.

Anchors inside the pager, inside either menu type, or inside our own menu SHALL
NOT be recognised. "sonraki" links to `/slug--123?p=2`, which is a title's
address used to mean "turn the page".

The WebView's native callout SHALL be suppressed on those links, because the
browser's own long-press otherwise answers the same gesture at the same moment
and the user gets both.

The gesture SHALL be abandoned when the finger travels further than the distance
the swipe uses to claim a gesture, so that scrolling a list of titles never opens
the menu, and the click that ends a hold SHALL NOT also open the title.

#### Scenario: Holding a title in a list

- **WHEN** the user holds a title row in gündem
- **THEN** a menu appears with the three options, and the title is not opened

#### Scenario: Holding a title anywhere else

- **WHEN** the user holds a title link in search results, in a profile's entry list, or in the header of the title being read
- **THEN** the same menu appears, keyed off the link rather than the page

#### Scenario: A tap is not a hold

- **WHEN** the user taps a title
- **THEN** no menu appears and the title opens as it always did

#### Scenario: Scrolling a list of titles

- **WHEN** a touch that started on a title row travels further than the swipe's threshold
- **THEN** the hold is abandoned and no menu appears

#### Scenario: The pager is not a title

- **WHEN** the user holds "sonraki" on a title page
- **THEN** no menu appears, even though the link carries that title's address

### Requirement: A held title yields its name and its address

"başlığı kopyala" SHALL copy the title's own words: the entry count Ekşi appends
to a list row is not part of the name, and the header's `data-title` is the name
verbatim.

"bağlantıyı kopyala" and "paylaş" SHALL carry the title's address with the list's
own sort parameter (`?a=`) removed, because it describes how the list the user
came from was ordered rather than what is being shared. Every other parameter
SHALL be preserved, since those select what is being shared.

Copying SHALL be performed by the host through `ClipboardManager`, not by the
page. The async clipboard API in a WebView is gated on a permission prompt the
app would have to answer on a third-party site's behalf, and the
`execCommand("copy")` fallback needs a live selection — which is what the hold
suppresses.

A copy confirmation SHALL be shown below Android 13 only. From 13 the platform
previews every copy itself, and a toast on top of it is the same message twice.

Choosing any option SHALL close the menu before acting, so that the share sheet
does not appear over a menu still standing behind it.

An option SHALL act on its own touch rather than on a click alone, and the menu
SHALL be usable from the instant it appears.

A touch sequence belongs to the element it began on for its whole life, so the
lift that ends a hold — and the click that lift leaves behind — are addressed to
the title, never to a button that has appeared under the finger. An option
therefore only ever receives a press aimed at it, and needs no flag, no timer and
no protective window. Only the leftover click on the title itself SHALL be
dropped, so the title does not open behind the menu; nothing SHALL suppress
clicks generally, because a rule that drops one click "wherever it lands" is what
made a chosen option do nothing.

No period during which the menu is visible and inert SHALL exist. Such a period
is one the user can tap into and be ignored by, and a period that fails to end
leaves a full-screen backdrop that answers nothing over a dimmed page, which is
indistinguishable from the app having frozen.

#### Scenario: A fast second tap

- **WHEN** the user lifts the finger that opened the menu and taps an option straight away
- **THEN** the option is chosen, rather than the tap falling through to the page

#### Scenario: Copying twice in a row

- **WHEN** the user copies the title and then immediately holds again and copies the link
- **THEN** both copies complete and no backdrop is left on the page

### Requirement: The held menu is modal and always dismissable

A touch landing anywhere outside the menu's card SHALL close it, on the touch
itself rather than on the click that may follow, so that dismissal does not
depend on clicks behaving. A touch on the card SHALL NOT close it, or no option
could be chosen.

The swipe navigation SHALL be suspended for any gesture occurring while the menu
is open, including a gesture already in progress when it opens — the menu appears
under a finger that is still down, and the drift of that finger coming off a hold
is not a page turn.

This is not only about unwanted navigation. The swipe stamps `will-change:
transform` on the element it slides, which makes that element the containing
block for everything fixed inside it; the surface is chosen by height, so once
the menu is open it is the menu's own backdrop. The menu then stops being
viewport-anchored and is positioned against the document instead, leaving a
dimmed page with the card somewhere far below the fold — visibly, a frozen app.
This is why the fault appeared only on a title page, which is where a page ring
exists, and never on the feed.

That dismissal SHALL NOT remove the backdrop from the document while the touch it
is answering is still in flight. A touch sequence belongs to the element it began
on, and taking that element out mid-sequence leaves the WebView holding a gesture
whose target no longer exists — after which the page stops answering touches at
all. The menu SHALL instead be hidden and made untouchable at once, which is the
whole of what the user perceives, and the node dropped once the gesture ends or
shortly after if no end is ever reported.

#### Scenario: Tapping past the menu

- **WHEN** the user touches anywhere outside the card
- **THEN** the menu closes, whatever state the rest of the page is in

#### Scenario: The page after a dismissal

- **WHEN** the menu has been dismissed by a touch outside it
- **THEN** the page still scrolls and still answers taps

#### Scenario: A finger drifting off a hold on a title page

- **WHEN** the menu opens and the finger that opened it moves before lifting
- **THEN** no drag begins, nothing is prefetched, and the card stays where the user can see and reach it

#### Scenario: Copying the name

- **WHEN** the user chooses "başlığı kopyala" on a gündem row
- **THEN** the clipboard holds the title's words without its entry count

#### Scenario: Copying the address

- **WHEN** the user chooses "bağlantıyı kopyala"
- **THEN** the clipboard holds the title's URL without the list's `?a=` sort parameter

#### Scenario: Sharing a title

- **WHEN** the user chooses "paylaş"
- **THEN** the Android share sheet opens carrying that title's URL, by the same path as the entry share

