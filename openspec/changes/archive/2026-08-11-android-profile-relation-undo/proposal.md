## Why

`bridge.js` never enqueues `BanMode.UNDOBAN`. Every injector hardcodes
`BanMode.BAN` — lines 828, 888, 898, 905, 954, 960 and 966 — so the browsing
surface can only ever add a relation, never take one away.

The extension does not work that way. `script.js:475-516` walks the same
`.relation-link` elements the bridge already queries, reads `data-added` on each,
and flips both the label and the mode:

| relation | `data-added="true"` | otherwise |
| --- | --- | --- |
| `data-add-caption="engelle"` | "engellemeyi bırak" → UNDOBAN / USER | "engelle" → BAN / USER |
| `data-add-caption="başlıklarını engelle"` | "başlıkları engellemeyi kaldır" → UNDOBAN / TITLE | "başlıklarını engelle" → BAN / TITLE |

`injectProfile` (`bridge.js:917`) already selects those elements. It reads them
only as an existence gate — `container.querySelectorAll(".relation-link").length
=== 0` at line 932 — and then throws the collection away. The state was two
attributes from the code that was already there.

Two consequences, and the second is worse than the missing feature:

1. A user who is already blocked has no per-user undo anywhere in the app. The
   bulk runs and the author list are the only paths, and both are the wrong
   granularity for "actually, not this one".
2. The profile of someone already blocked still offers a button reading
   "engelle". Tapping it re-sends a block for a relation that exists.
   `RelationClient` treats the resulting `2` as success (`RelationClient.kt:21`),
   so the operation reports success for a no-op and the user is left believing
   the button did the opposite of what it says.

`injectProfile` also removes Ekşi's own red `#button-blocked-link` (line 943),
mirroring `script.js:489`. That is correct where our button covers both
directions and wrong today, because it takes away the one native control that
still did the undo.

## What Changes

- `injectProfile` reads `data-add-caption` and `data-added` off each
  `.relation-link` and emits the matching direction, instead of appending three
  fixed BAN items.
- The block item and the title item each carry the label and the `banMode` their
  relation's state calls for.
- "takipçilerini engelle" stays BAN-only. It is not a relation on this profile —
  it is an operation over someone else's follower list — so there is no state to
  read and nothing to undo.
- `#button-blocked-link` removal is kept, and is only correct once the item
  replacing it covers both directions.

## Non-goals

- **Undoing a mute from the profile.** Ekşi renders no `.relation-link` for the
  mute relation, so its state is not on the page. The extension does not offer
  it either. Bulk "Tüm Sessizleri Kaldır" and the author list stay the paths.
  Guessing the state and offering "sessizden çıkar" unconditionally would be a
  button that silently does nothing most of the time.
- The entry-menu and title-menu injectors. Those act on other people's entries
  and titles, not on a relation whose current state the page shows.
- Any change to the ban sources, the engine, or the task factory. This is the
  browsing surface only.

## Impact

- Affected specs: `android-browsing`
- Affected code: `android/webview/src/main/assets/bridge.js` (`injectProfile`);
  `android/webview/src/test/kotlin/.../BridgeMapperTest.kt`;
  `android/app/src/test/kotlin/.../ParityTest.kt`
- No change to `frontend/app/` runtime code.
