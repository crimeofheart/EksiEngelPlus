## ADDED Requirements

### Requirement: Third-party advertising and analytics are not loaded

The WebView SHALL drop requests to third-party advertising, analytics and
audience-measurement hosts, returning an empty successful response rather than an
error so a script expecting one fails fast instead of retrying.

`onPageFinished` waits for every subresource and the page's own scripts queue
behind them, so these hosts delay the very controls the app exists to inject.
Measured on a real device: one homepage load fetched eleven third-party hosts and
took 18.2 seconds from `loadUrl` to finished, against 0.3 seconds for the
document itself. Blocking them took a cold start from 23.4 seconds to 6.0.

The list SHALL stay narrow and evidence-based. Ekşi's own hosts, including
`ekstat.com` which serves the site's images, and the font CDNs are never blocked
-- blocking fonts trades a load win for a visible rendering change.

This binds the Android client only. The extension is unchanged.

#### Scenario: An ad host is requested

- **WHEN** the page requests a host on the blocklist
- **THEN** an empty successful response is returned and no network request is made

#### Scenario: The site's own assets are untouched

- **WHEN** the page requests `eksisozluk.com`, `ekstat.com` or a font CDN
- **THEN** the request proceeds normally

#### Scenario: Matching is on the registrable suffix

- **WHEN** a host merely contains a blocked name, such as `mygoogle-analytics.com`
- **THEN** it is not blocked, because matching is on the host or a dot-prefixed suffix

### Requirement: Blocked ad slots do not leave holes

Containers reserved for the blocked hosts SHALL be collapsed, via a stylesheet
injected at document start rather than a scan, so the slots never occupy space at
first paint.

Dropping the requests alone leaves the reserved space behind, and an empty slot
is arguably worse than an ad: a hole in the page with no explanation for it.

#### Scenario: A page with reserved ad slots renders

- **WHEN** a page containing ad containers is displayed
- **THEN** those containers occupy no space, and no gap appears where an ad would have been

#### Scenario: The collapse happens before paint

- **WHEN** the document begins rendering
- **THEN** the slots are already collapsed, rather than visibly disappearing a moment later

### Requirement: Sub-frames are not routed off-site

The off-site navigation policy SHALL apply to main-frame navigation only.

That policy exists to stop a user being carried away by a link they tapped, and
an iframe is not a tap. Treating the profile page's embedded `eksiseyler.com`
frame as an off-site navigation meant trying to hand a widget to an external
browser and stalling the page for about thirty seconds. Cross-origin framing is
already governed by the embedded site's own headers.

#### Scenario: A page embeds a cross-origin frame

- **WHEN** a sub-frame navigates to a host outside the allowlist
- **THEN** the WebView handles it normally and no external app is offered

#### Scenario: A tapped link still leaves

- **WHEN** the user taps a link to an off-site host in the main frame
- **THEN** it is handed to an external browser as before
