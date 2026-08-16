## ADDED Requirements

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
