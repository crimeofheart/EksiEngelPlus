## ADDED Requirements

### Requirement: Content is never drawn under a system bar

Every activity SHALL pad its content by the system bar and IME insets, so no
screen puts a control or a line of text under the status bar, the navigation bar
or the keyboard.

`android:fitsSystemWindows="true"` SHALL NOT be relied on for this. It is what
the app used, and it stopped being sufficient: from targetSdk 35 the framework
draws every window edge to edge, and `windowOptOutEdgeToEdgeEnforcement` is
ignored at targetSdk 36. The failure was asymmetric — the status bar still came
out right while the navigation bar covered the bottom of every screen — which is
the worst shape for it to take, because it reads as a cosmetic bottom-margin bug
rather than as a mechanism that is no longer running.

There SHALL be exactly one mechanism. A layout that declares
`fitsSystemWindows` *and* an activity that applies insets would pad twice on any
platform where both work, and the second one only shows up on the devices the
developer does not have.

Padding SHALL be applied to the activity's content view rather than to each
layout's root, so a screen gets this by being an activity and cannot get it
wrong in XML.

Insets SHALL be returned unconsumed. A bottom sheet, a snackbar or a scrolling
container that wants to know where the bars are must still be able to find out.

#### Scenario: The navigation bar does not cover content

- **WHEN** any screen is displayed on a device with a navigation or gesture bar
- **THEN** its bottom-most content ends above that bar

#### Scenario: The status bar does not cover content

- **WHEN** any screen is displayed
- **THEN** its top-most content begins below the status bar

#### Scenario: The keyboard does not cover content

- **WHEN** the IME opens over a screen that accepts text
- **THEN** the content is padded clear of it

#### Scenario: Padding is applied once

- **WHEN** a layout is inspected
- **THEN** it does not declare `fitsSystemWindows`, because the activity applies the insets
