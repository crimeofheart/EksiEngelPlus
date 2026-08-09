## 1. One mechanism

- [x] 1.1 Add `core:ui` with `fitContentInsideSystemBars()` — pads `android.R.id.content` by `systemBars() or ime()`, returns the insets unconsumed
- [x] 1.2 Request an inset pass on attach, so a cold start that reaches `onCreate` after the window already has its insets is padded immediately rather than on the next relayout
- [x] 1.3 Call it from all seven activities: Browser, Lists, Operations, AuthorList, Settings, Help, ReleaseNotes
- [x] 1.4 Remove `fitsSystemWindows` from the five layouts that declared it, so the two mechanisms cannot both fire
- [x] 1.5 Wire `:core:ui` into `app`, `feature:lists` and `feature:settings`

## 2. Verification

- [x] 2.1 `./gradlew :app:assembleDebug testDebugUnitTest lintDebug`
- [x] 2.2 Add a test that no layout declares `fitsSystemWindows`, so the second mechanism cannot come back
- [ ] 2.3 Verify on device: the page, the settings list and the author list buttons all end above the gesture bar
- [ ] 2.4 Verify on device with three-button navigation as well as gestures — the inset differs and only one of the two was ever looked at
- [ ] 2.5 Verify the keyboard case on the author list, which is the only screen with a large text field
- [ ] 2.6 Run `openspec validate android-system-bar-insets` clean, then `openspec archive android-system-bar-insets`
