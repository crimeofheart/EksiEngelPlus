## Why

The navigation bar sits on top of the bottom of every screen. The page's last
line, the bottom of the settings list and the buttons under the author list are
all drawn underneath it.

Every layout carries `android:fitsSystemWindows="true"` and has since the shell
was built. It used to be enough. From targetSdk 35 the framework draws every
window edge to edge, and `android:windowOptOutEdgeToEdgeEnforcement` — the
documented escape hatch — is ignored at targetSdk 36, which this app is
(`app/build.gradle.kts:62`). The status bar still comes out right, so the bug
reads as a bottom-only problem and looks cosmetic; it is the same missing
mechanism at both ends, and only one end happened to survive.

## What Changes

- Add `core:ui` with one function: pad `android.R.id.content` by the system bar
  and IME insets.
- Call it from all seven activities, and drop `fitsSystemWindows` from the five
  layouts that carried it, so there is one mechanism rather than two that
  disagree.
- Insets are returned unconsumed, so a child that needs to know where the bars
  are still finds out.

## Impact

- Affected specs: `android-browsing`
- Affected code: all seven activities; the five layouts that declared
  `fitsSystemWindows`; new module `core:ui`
- No change to `frontend/app/` runtime code.
