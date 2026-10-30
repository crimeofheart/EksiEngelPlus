## ADDED Requirements

### Requirement: A challenge is recognised by its answer

Binds: **extension**. Android makes its requests from the WebView page and never sees the challenge.

A response to an Ekşi Sözlük request SHALL count as a Cloudflare challenge when its status is `403` or
`503` and either its `cf-mitigated` header is `challenge` or its body contains
`/cdn-cgi/challenge-platform/`. Any other 403 or 503 SHALL be handled as it is today.

#### Scenario: Challenge answer
- **WHEN** `GET https://eksisozluk.com/` answers 403 with `cf-mitigated: challenge`
- **THEN** the request counts as challenged, not as Ekşi being unreachable

#### Scenario: Ordinary forbidden answer
- **WHEN** a request answers 403 with neither the header nor the challenge page
- **THEN** it is handled exactly as before this change

### Requirement: Ekşi requests go through one routing function

Binds: **extension**.

Every request the extension's background makes to Ekşi Sözlük -- the access check, every scrape in
`scrapingHandler.js`, the relation writes `POST /userrelation/addrelation/<id>?r=<type>` and
`POST /userrelation/removerelation/<id>?r=<type>` in `relationHandler.js`, and the account checker's `GET /biri/<nick>` and
`GET /son-entryleri?nick=<nick>` -- SHALL go through one routing function. It SHALL keep each
request's method, headers (including `x-requested-with: XMLHttpRequest`), and body, and SHALL return
the status, the `Retry-After` and `cf-mitigated` headers, and the body text, so callers handle 429 and
parse pages exactly as they do now.

#### Scenario: Unchallenged connection
- **WHEN** no request is challenged
- **THEN** every request is made directly from the background, as today, and no tab is used

### Requirement: A challenged request is retried through an Ekşi tab

Binds: **extension**.

When a direct request is challenged, the routing function SHALL send the same request to the content
script of an open tab whose URL is under the configured Ekşi base URL, which SHALL make it from the
page and return the answer. The run SHALL keep using the tab route for its remaining requests, and
SHALL try the direct route again at the start of the next run.

#### Scenario: Run under a challenge with an Ekşi tab open
- **WHEN** a run starts, the direct access check is challenged, and an Ekşi tab is open
- **THEN** the access check, the scrapes and the relation writes all go through that tab, and the run
  completes as it would without the challenge

#### Scenario: Tab navigates away mid-run
- **WHEN** the tab in use navigates, closes, or stops answering
- **THEN** the request is sent to another open Ekşi tab, or to a new one, and is not counted as failed

### Requirement: A run opens a tab only when it has to, and closes it

Binds: **extension**.

When a run needs the tab route and no Ekşi tab is open, the extension SHALL open
`<base URL>/` in a background tab (not focused), wait until the page there is no longer the
challenge page, and close the tab when the run ends if it opened it. When the challenge does not
clear on its own within 30 seconds (an interactive check), the run SHALL tell the user to complete the
check in that tab, and SHALL wait for it instead of failing. The account checker SHALL NOT open a tab:
without an open Ekşi tab it SHALL stop as refused.

#### Scenario: No Ekşi tab open
- **WHEN** a run is challenged and no Ekşi tab is open
- **THEN** the extension opens one in the background, the run continues through it, and the tab is
  closed when the run ends

#### Scenario: Interactive check
- **WHEN** the opened tab still shows the challenge after 30 seconds
- **THEN** the run's status says to complete the Ekşi Sözlük check in the opened tab, and the run
  continues once the page loads

#### Scenario: Checker without a tab
- **WHEN** the account checker is challenged and no Ekşi tab is open
- **THEN** it opens no tab and reports the batch as refused

### Requirement: The content script fetches only its own origin, for the extension

Binds: **extension**.

The content script SHALL make a request only when the message comes from this extension
(`sender.id === chrome.runtime.id`) and the URL's origin equals the page's own origin. Any other
request SHALL be refused without being made.

#### Scenario: Foreign URL
- **WHEN** the content script is asked to fetch a URL outside the page's origin
- **THEN** it refuses and makes no request
