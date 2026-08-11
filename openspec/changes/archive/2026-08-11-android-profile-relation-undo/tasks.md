## 1. Read the relation state that is already selected

- [x] 1.1 In `injectProfile` (`bridge.js:917`), replace the existence-gate use of `.relation-link` with a pass that indexes the links by `data-add-caption` and records `data-added === "true"` for each; keep the early return when the collection is empty, since that is what keeps our items off the user's own profile
- [x] 1.2 Emit the block item from that state: label "engellemeyi bırak" with `banMode` UNDOBAN and `targetType` USER when added, otherwise `muteWord("engelle", "sessize al")` with `banMode` BAN and the config-derived target
- [x] 1.3 Emit the title item from that state: "başlıkları engellemeyi kaldır" with UNDOBAN when added, otherwise "başlıklarını engelle" with BAN, `targetType` TITLE either way
- [x] 1.4 Leave "takipçilerini engelle" BAN-only, and say why in a comment — no `.relation-link` describes it, so there is no state to invert
- [x] 1.5 Skip an item whose caption has no matching link, so a relation Ekşi does not expose (mute) produces no control rather than a dead one

## 2. Hold the behaviour down

- [x] 2.1 Extend `BridgeInjectionTest` with the four state combinations of the two relations, asserting label and `banMode` for each. Not `BridgeMapperTest`: that tests the Kotlin mapping, where UNDOBAN already arrived correctly — the decision under test is made in the page, so it needs a fixture with the two `.relation-link` attributes on it
- [x] 2.2 Add a case asserting the undo carries `targetType` USER under `enableMute`, since the mute-aware label is what makes the wrong target look plausible; and its mirror, that the *add* direction is still mute-aware, so the label is not merely cosmetic
- [x] 2.3 Add a `ParityTest` case asserting `bridge.js` contains a `BanMode.UNDOBAN` enqueue, so the whole browsing surface cannot silently go back to add-only
- [x] 2.4 `./gradlew :app:assembleDebug testDebugUnitTest lintDebug`

## 3. Verification

- [x] 3.1 Verify on device: a blocked user's profile offers "engellemeyi bırak", and running it clears the block
- [x] 3.2 Verify the same profile afterwards offers "engelle" again, without a reload beyond the site's own
- [x] 3.3 Verify the title relation inverts independently — block a user's titles only, and check the two items disagree
- [x] 3.4 Verify with `enableMute` on that the undo still removes the block rather than reporting success against the mute relation
- [x] 3.5 Verify a profile with no relation links at all (your own) still receives no injected items
- [x] 3.6 `cd frontend/app && npm run check && npm run package`
- [x] 3.7 Run `openspec validate android-profile-relation-undo` clean, then `openspec archive android-profile-relation-undo`
