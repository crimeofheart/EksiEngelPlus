## 1. Enum and record shape

- [ ] 1.1 Add `OPERATION_RETRY` to `enums.ClickType` in `frontend/app/assets/js/enums.js`.
- [ ] 1.2 Extend the `metadata` object built alongside single-target dispatches
      (background.js:461 path, `wrapperProcessHandler = processHandler.bind(...)`)
      to retain `entryUrl`, `authorName`, `authorId`, `targetType`, `clickSource`,
      `titleName`, `titleId`, `timeSpecifier` so the queue item exposes them via
      `processQueue.itemAttributes`.
- [ ] 1.3 Thread the same fields through to whatever record backs the completed
      table (the call site(s) of `insertCompletedProcessesTable`,
      notification.js:1430) so completed/failed rows retain them after the task
      leaves the live queue.
- [ ] 1.4 Confirm via `resumableOperation.js` checkpoint persistence that these
      fields survive a service worker restart while a task is still queued
      (they're stored in `chrome.storage` alongside other operation state).

## 2. Retry action

- [ ] 2.1 In `notification.js`, add a "Tekrarla" button/handler to
      `updatePlannedProcessesTable` rows, rendered only when the row's record has
      the target-identity fields from Task 1.
- [ ] 2.2 Add the same button/handler to `insertCompletedProcessesTable` rows
      (both FAILED and successful), same visibility rule.
- [ ] 2.3 Implement the click handler: build the same message shape
      `EksiEngel_sendMessage` sends (banSource, banMode, entryUrl, authorName,
      authorId, targetType, clickSource, titleName, titleId, timeSpecifier) from
      the stored record and dispatch it via `chrome.runtime.sendMessage` (reusing
      `sendMessageWithPromise`, notification.js:665, or equivalent) to the
      existing background.js listener — no new message type.
- [ ] 2.4 Send `commHandler.sendAnalyticsData({ click_type:
      enums.ClickType.OPERATION_RETRY })` on click, before dispatch.
- [ ] 2.5 Manually verify: fail a task on purpose (e.g. block a nonexistent user
      or trigger a timeout), then retry it from both the queue view (if still
      visible) and the completed/failed history view; confirm a new task
      enqueues and runs with identical parameters.

## 3. Navigate ("Git") action

- [ ] 3.1 Determine the existing title→URL construction used elsewhere for
      TargetType.TITLE tasks (check `processHandler`/scrapingHandler title URL
      building) and reuse it — do not invent a new title URL scheme.
- [ ] 3.2 Add a "Git" button/handler to both task tables, rendered when the
      row's record has entryUrl, authorName, or titleName/titleId; resolves to
      `{origin}/entry/{entryId}`, `{origin}/biri/{authorName}`, or the title URL
      from 3.1 respectively.
- [ ] 3.3 Implement the click handler using `chrome.tabs.create({ url })`,
      matching the pattern in `popup.js` (`handleOpenFaq`,
      `handleOpenNotification`).
- [ ] 3.4 Manually verify "Git" against a target that is currently blocked and
      one that is currently muted, confirming the tab opens the correct page
      either way (not a 404/redirect artifact of the block/mute state).

## 4. Styling and layout

- [ ] 4.1 Add "Tekrarla"/"Git" button styles to
      `frontend/app/assets/css/customNotification.css`, consistent with existing
      row action styling (see `.stats-btn` family for the established button
      look).
- [ ] 4.2 Confirm buttons render sanely at existing table widths for both queue
      and completed tables; no layout overflow when both buttons are present on
      a row alongside existing content.

## 5. Verification

- [ ] 5.1 Load unpacked in Chrome (`npm run switch:chrome`), exercise retry and
      navigate end-to-end per CLAUDE.md's load/reload instructions.
- [ ] 5.2 Load temporary add-on in Firefox (`npm run switch:firefox`), repeat
      5.1 — this feature touches only `notification.js`/`background.js`/
      `enums.js`, shared verbatim between both builds, but must be verified on
      both per the repo's Chrome/Firefox parity requirement.
- [ ] 5.3 `cd frontend/app && npm run check && npm run package` — confirm the
      build still passes and both zips produce cleanly with no changes needed to
      manifest variants.
