## Why

The İşlem durumu (task/operation status) screen lists every queued, running, and
completed task, but a task there is a dead end: if it FAILED (timeout, rate limit,
etc.) the only way to retry is to leave the page, find the entry/user/title again,
and re-issue the action from scratch. There is also no way to jump back to where a
task was started to pick a related action (e.g. block the author after already
blocking their favers). Both are one click away everywhere else in the extension's
UI conventions (menus always act on a visible entry/profile) but missing here,
where the task itself is the only surviving reference to that entry/user/title.

## What Changes

- Add a "Tekrarla" (Repeat) action to each row of the İşlem durumu queue table
  (`updatePlannedProcessesTable`) and completed-tasks table
  (`insertCompletedProcessesTable`) in `frontend/app/assets/js/notification.js`.
  Re-enqueues the same task through the existing `EksiEngel_sendMessage` /
  `processHandler` path in `frontend/app/assets/js/background.js`, using the
  banSource/banMode/entryUrl/authorName/authorId/targetType/clickSource/
  titleName/titleId/timeSpecifier the original task was created with. Available
  for both FAILED and already-completed/successful tasks, and for queued items
  (cancels-and-requeues is out of scope — queued items just get a duplicate
  enqueue).
- Add a "Git" (Go to) action to the same rows that opens the entry/profile/title
  URL the task originated from, in a new tab, using the task's stored
  entryUrl/authorName/titleId — resolved as a direct URL, not a page search, so it
  still works when the target is already blocked or muted.
- Persist enough of each task's original parameters (entryUrl, authorName,
  authorId, titleName, titleId) in the planned/completed process records so both
  actions have what they need after the task leaves the live queue. Today these
  records already carry banSource/metadata for display; the target identity
  fields are not reliably retained for completed items.
- Record a "Tekrarla" click as its own value in `enums.ClickType` (extension
  analytics enum) so it reaches the backend `Action`/`ClientAnalytic` telemetry
  as a distinguishable event, sent via the existing
  `commHandler.sendAnalyticsData` path — instead of being indistinguishable from
  a fresh, user-initiated action of the same banSource.
- No backend schema change needed: `ClickType` telemetry rows already carry an
  open `click_type` value (see `backend/django_EksiEngel/client_data_collector/models.py`),
  so a new value shows up in the existing Django admin (`ClickType`,
  `ClientAnalytic`) without a migration. Confirm this during design; add a
  migration only if the existing telemetry model can't represent it.

## Capabilities

### New Capabilities
- `task-history-actions`: retry and navigate-to-source actions on İşlem durumu
  queue/completed task rows, plus the telemetry needed to distinguish a retry
  click from a fresh action click in the admin.

### Modified Capabilities
(none — no existing spec currently covers the İşlem durumu screen or extension
click telemetry)

## Impact

- `frontend/app/assets/js/notification.js` — task table rendering
  (`updatePlannedProcessesTable`, `insertCompletedProcessesTable`,
  `insertCompletedProcessesTable`'s stored history) gains action buttons/handlers.
- `frontend/app/assets/js/background.js` — process queue item shape may need to
  retain original target params through to completion so "Tekrarla"/"Git" can
  read them back; `processHandler`/`EksiEngel_sendMessage` reused as-is for
  re-enqueue.
- `frontend/app/assets/js/enums.js` — new `ClickType` value for "retry clicked".
- `frontend/app/assets/js/commHandler.js` (or wherever `sendAnalyticsData` is
  invoked from) — one new call site for the retry click.
- Backend: none expected beyond the existing open-ended `ClickType`/`ClientAnalytic`
  telemetry tables already surfaced in Django admin
  (`backend/django_EksiEngel/client_data_collector/admin.py`,
  `backend/django_EksiEngel/api/admin.py`).
- Chrome and Firefox both ship this — no manifest changes, no jsdom impact.
- Frontend runtime code IS touched (notification.js, background.js, enums.js).
  Android is NOT touched.
