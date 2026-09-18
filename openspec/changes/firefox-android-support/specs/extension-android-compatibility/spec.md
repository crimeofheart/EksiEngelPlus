## ADDED Requirements

All requirements below bind the **extension** client only (`frontend/app/`), and of its two
builds only the Firefox one — Chrome for Android does not load extensions, so
`manifest.chrome.json` is out of scope. The Android app (`android/`) is unaffected.

### Requirement: The Firefox build declares Android compatibility

`manifest.firefox.json` SHALL carry `browser_specific_settings.gecko_android` with a
`strict_min_version`. Firefox for Android only accepts an add-on that declares this key (it was
added in Firefox for Android 113), and AMO uses it to decide whether the listing is offered to
Android users at all. The declared minimum SHALL NOT be below the desktop
`browser_specific_settings.gecko.strict_min_version`, since both builds run the same code; it MAY
be above it, and is: the manifest's own `gecko.data_collection_permissions` reached desktop in 140
but Android only in 142, so the Android floor is `"142.0"` against a desktop `"140.0"`.

#### Scenario: The generated Firefox manifest is Android-eligible
- **WHEN** `npm run switch:firefox` has written `manifest.json`
- **THEN** it contains `browser_specific_settings.gecko_android.strict_min_version` (`"142.0"`)
- **AND** that value is not below `browser_specific_settings.gecko.strict_min_version` (`"140.0"`)

#### Scenario: Every manifest key is supported at the declared Android floor
- **WHEN** `web-ext lint` runs over the packaged Firefox zip
- **THEN** it reports no `KEY_FIREFOX_ANDROID_UNSUPPORTED_BY_MIN_VERSION` warning and no errors

#### Scenario: The Chrome build is untouched
- **WHEN** `manifest.chrome.json` is read after this change
- **THEN** it contains no `browser_specific_settings` key at all, exactly as before

### Requirement: The build refuses a Firefox manifest that lost Android compatibility

`npm run check` SHALL fail when `manifest.firefox.json` is missing `gecko_android`, or when its
`strict_min_version` disagrees with the `gecko` one. The key is invisible in day-to-day use — it
changes nothing on desktop — so only a build-time assertion keeps it from being dropped in a
future manifest edit.

#### Scenario: Missing key fails the check
- **WHEN** `browser_specific_settings.gecko_android` is deleted from `manifest.firefox.json` and
  `npm run check` runs
- **THEN** the command exits non-zero naming the missing key

#### Scenario: An Android floor below the desktop one fails the check
- **WHEN** `gecko.strict_min_version` is `"140.0"` and `gecko_android.strict_min_version` is
  `"115.0"`, and `npm run check` runs
- **THEN** the command exits non-zero naming both values

#### Scenario: An Android floor above the desktop one is allowed
- **WHEN** `gecko.strict_min_version` is `"140.0"` and `gecko_android.strict_min_version` is
  `"142.0"`, and `npm run check` runs
- **THEN** the command exits zero

#### Scenario: A correct manifest still passes
- **WHEN** both keys agree and `npm run check` runs
- **THEN** the command exits zero and still reports the seven-location version agreement

### Requirement: Extension pages lay out at device width

Every HTML page the extension ships — `popup.html`, `notification.html`, `faq.html`,
`welcome.html`, `authorListPage.html`, `documentation.html` — SHALL declare
`<meta name="viewport" content="width=device-width, initial-scale=1">`. Without it Android lays a
page out at the ~980px fallback viewport and scales it down, which both shrinks the text below
readability and prevents the `max-width` breakpoints already present in `customNotification.css`
(1024px, 768px, 480px) from ever matching.

#### Scenario: Every shipped page declares a viewport
- **WHEN** the `.html` files under `frontend/app/assets/html/` are inspected
- **THEN** each one has a `width=device-width` viewport meta in its `<head>`

#### Scenario: Phone-width rules take effect
- **WHEN** `notification.html` is opened on a 412px-wide Android viewport
- **THEN** the `@media (max-width: 480px)` block applies — the header collapses to its compact
  form and the stats cards stack in a single column

### Requirement: The popup fits the panel it is given

The popup SHALL size itself to the available width rather than to a fixed 350px box. On desktop
Firefox and Chrome the panel is sized by the content, so the popup MUST keep its present ~350px
appearance; on Firefox for Android the popup is rendered full-screen at device width, where a
fixed 350px body either leaves dead space or overflows on a narrow phone.

#### Scenario: Desktop popup is unchanged
- **WHEN** the popup opens in a viewport wider than 350px
- **THEN** its body is 350px wide, as today

#### Scenario: Phone popup takes the panel
- **WHEN** the popup opens in a viewport of 480px or less — the full-screen panel Firefox for
  Android gives it
- **THEN** the body spans that width, and its two buttons span the body

#### Scenario: Narrow phone popup does not overflow
- **WHEN** the popup opens in a viewport narrower than 350px
- **THEN** the page scrolls vertically only — no horizontal scrollbar and no clipped buttons

### Requirement: Queue and history tables stay inside the page on a phone

The operations tables on `notification.html` SHALL scroll horizontally within their own container
rather than widening the document. A table with the task, status, target and action columns cannot
fit a phone width, and today nothing bounds it, so the whole page is pushed wide and every other
element inherits the horizontal scroll.

#### Scenario: A wide table does not widen the page
- **WHEN** the planned-processes table renders with all its columns on a 412px viewport
- **THEN** the document does not scroll horizontally
- **AND** the table itself can be swiped sideways within its card

### Requirement: Row actions are touch-sized

The per-row action controls — `.task-action-btn` ("Tekrarla"/"Git") and the `.stats-btn` family —
SHALL present a hit target of at least 40×40 CSS px at viewports of 768px and below. At their
desktop size they are a few px tall, which is below what a finger can reliably hit and is the
difference between the retry feature being usable on a phone and not.

#### Scenario: Retry and navigate are tappable
- **WHEN** a failed task row renders at 412px viewport width
- **THEN** each of its "Tekrarla" and "Git" buttons measures at least 40px in both dimensions

### Requirement: Hover-only help text is reachable by touch

`tooltip.css` SHALL reveal the tooltip bubble on keyboard focus and on activation, not only on
`:hover`. A touch device has no hover state, so the FAQ page's explanatory bubbles are today
unreachable on a phone; the same change makes them keyboard-reachable on desktop.

#### Scenario: Tapping a tooltip shows it
- **WHEN** a `.tooltip` element is tapped on a touch device
- **THEN** its `::after` bubble and `::before` arrow become visible

#### Scenario: Focusing a tooltip shows it
- **WHEN** a `.tooltip` element receives keyboard focus
- **THEN** its bubble becomes visible, and hiding on blur

### Requirement: The in-page controls work against the mobile site layout

The content script SHALL keep matching Ekşi Sözlük's markup when the site is served to a mobile
Firefox user agent. The mobile layout serves the same containers the script binds to — the entry
dropdown (`.dropdown-menu`, `ul.toggles-menu`, `.other .dropdown-menu`), the in-topic search menu
(`#in-topic-search-options`) and the profile relation container (`.profile-buttons` with its
`.relation-link` children) — so no selector change is required, and any future change MUST be made
without breaking either layout.

#### Scenario: Entry menu buttons appear on a phone
- **WHEN** an entry list is opened in Firefox for Android on `eksisozluk.com` while logged in
- **THEN** the injected "engelle"/"sessize al"/"takip et" items appear in the entry's dropdown
  menu, marked with `data-eksiengelProcessed="true"` exactly as on desktop

#### Scenario: Profile buttons appear on a phone
- **WHEN** a profile page (`/biri/<nick>`) is opened in Firefox for Android while logged in
- **THEN** the injected relation buttons are appended to `.profile-buttons` alongside the site's
  own `.relation-link` buttons

### Requirement: The Android build is the same artifact

`npm run package` SHALL keep producing exactly two zips, and the Firefox zip SHALL serve both
desktop and Android from the single AMO listing. No Android-specific zip, manifest variant, or
build step is introduced; in particular the `jsdom` substitution and the 5 MB AMO text-file ceiling
enforced by `package` continue to apply unchanged.

#### Scenario: Packaging output is unchanged in shape
- **WHEN** `npm run package` runs after this change
- **THEN** it writes `eksiengelplus-<version>-chrome.zip` and `eksiengelplus-<version>-firefox.zip`
- **AND** the Firefox zip still reports the `jsdom` stub substitution and stays under the 5 MB
  per-text-file limit
