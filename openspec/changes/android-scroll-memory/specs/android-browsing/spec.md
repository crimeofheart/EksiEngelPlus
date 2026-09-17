## ADDED Requirements

### Requirement: A page comes back to where it was left

The bridge SHALL remember the scroll position of each address and SHALL restore
it when the user navigates back to that address, on every page the site scrolls.

The position SHALL be kept in `sessionStorage`, keyed by path and query: it is
worth remembering for as long as the user is browsing and no longer. The store
SHALL be bounded, dropping the least recently written addresses, because Ekşi's
pagination means one session can touch a great many of them. Storage being
unavailable SHALL cost the position and nothing else.

Restoration SHALL happen on `popstate` and on a document load whose navigation
type is `back_forward`, and SHALL NOT happen on any other load. Both are needed
and neither covers the other: opening an entry is a document load whose own
restoration cannot survive rows that arrive by XHR afterwards, while paging
within a title is `pushState`, where no navigation happened and nothing is
restored at all.

Because the rows a remembered position refers to may not exist yet, restoration
SHALL retry while the document is shorter than that position, bounded by a
deadline. A single `scrollTo` is clamped to the height the document has at that
moment, which on these lists is a fraction of the height it will reach.

`history.scrollRestoration` SHALL be set to `manual`, so the renderer's own
attempt cannot pull the page back to the top underneath this one.

Going back to an address with no remembered position SHALL put the page at the
top when the navigation was in-page. Turning off the renderer's restoration turns
off its reset too, so a `popstate` onto a page that was never scrolled would
otherwise leave the user at whatever depth the page they are leaving had reached.
A document load starts at the top by itself and needs no help.

Restoration SHALL be abandoned on the first touch, wheel or key event: the user
has decided where they want to be, and continuing to move them is worse than
never having restored anything. An address carrying a fragment SHALL NOT be
restored, because the site is aiming at an anchor of its own.

#### Scenario: Returning to a list

- **WHEN** the user scrolls down gündem, opens a title, and goes back
- **THEN** the list is where they left it, not at the top

#### Scenario: Returning to a page reached by in-page pagination

- **WHEN** the user pages within a title via `pushState` and goes back
- **THEN** the previous page is restored to its remembered position

#### Scenario: A list still being filled

- **WHEN** the remembered position is below the height the document has on load
- **THEN** the position is reached once the remaining rows arrive

#### Scenario: Returning to a page that was never scrolled

- **WHEN** the user goes back in-page to an address with no remembered position
- **THEN** the page is at the top, not where the page being left had reached

#### Scenario: Arriving somewhere new

- **WHEN** the user opens a page forward rather than returning to it
- **THEN** it starts wherever the site puts it, at the top

#### Scenario: The user takes over

- **WHEN** the user touches the page while a position is being restored
- **THEN** restoration stops and the page stays where the user put it

#### Scenario: The site is aiming at an anchor

- **WHEN** the address carries a fragment
- **THEN** no position is restored and the site's own anchor is left alone
