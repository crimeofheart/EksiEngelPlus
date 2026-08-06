# android-browsing

Binds: Android. Ports `frontend/app/assets/js/script.js`.

## ADDED Requirements

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
| `.profile-buttons` | engelle/sessize al, başlıklarını engelle, takipçilerini engelle |
| `#user-notifications` | a transient confirmation toast |

The entry menu SHALL be identified by its contents, not its position: four
`.dropdown-menu` elements render per page, so the extension's text match against
`['engelle','modlog','şikayet','mesaj']` (`script.js:315`) is required rather than
defensive.

`ul.toggles-menu` SHALL NOT be relied on. It matches zero elements on every page
type, logged in and out.

Labels SHALL follow config, showing mute wording when `enableMute` is set.

#### Scenario: Entry menu identified among four dropdowns

- **WHEN** an entry page is processed
- **THEN** the menu containing the expected items receives the injection and the other three do not

#### Scenario: Labels follow settings

- **WHEN** `enableMute` is enabled
- **THEN** items read "sessize al" instead of "engelle"

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

#### Scenario: Labels are correct on first paint

- **WHEN** a page loads with `enableMute` set
- **THEN** the first render of the menu already shows mute wording

#### Scenario: A settings change reaches an open page

- **WHEN** config changes while a page is open
- **THEN** the page is notified and re-renders its labels

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

#### Scenario: Logging in resumes a parked operation

- **WHEN** the user logs in while an operation waits in `PAUSED_AUTH`
- **THEN** the operation is offered for resumption

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

#### Scenario: A lookalike host is not captured

- **WHEN** a link points at a host merely containing `eksi` inside another word, such as `meksika-haber.com`
- **THEN** it is treated as external and opens outside the WebView

#### Scenario: A genuinely external link leaves

- **WHEN** a link points at an unrelated host
- **THEN** it opens outside the WebView

#### Scenario: A new-window link does not escape

- **WHEN** a link declares `target="_blank"`
- **THEN** it loads in the same WebView

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
