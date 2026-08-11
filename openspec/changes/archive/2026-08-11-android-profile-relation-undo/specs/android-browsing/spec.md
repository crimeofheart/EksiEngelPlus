## MODIFIED Requirements

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
