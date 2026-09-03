## Context

İşlem durumu (`frontend/app/assets/js/notification.js`) renders two tables:

- **Queue table** — `updatePlannedProcessesTable(plannedProcesses)` (notification.js:1498),
  fed by `processQueue.itemAttributes`, one row per queued/running
  `wrapperProcessHandler`. Each queue entry carries `banSource`, a `metadata`
  object (`operationNotes`, `requiresUserInteraction`, `targetTypes`), and a
  `creationDateInStr` string, built by `createWrapperProcessHandler`
  (background.js:192-201). It does **not** currently carry the raw
  entryUrl/authorName/authorId/titleName/titleId the task was created from —
  those live only in the closure captured by `handler` (e.g. background.js:605
  `processHandler(banSource, banMode, entryUrl, singleAuthorName, singleAuthorId,
  targetType, clickSource, titleName, titleId, timeSpecifier, listAction)`), which
  is not serializable/inspectable from the notification page.
- **Completed table** — `insertCompletedProcessesTable(banSource, successfulAction,
  performedAction, plannedAction, errorStatus, operationMetadata, timestamp)`
  (notification.js:1430). Same gap: `operationMetadata` today holds display
  strings, not the original target identity.

Single-user actions are dispatched from content scripts via `EksiEngel_sendMessage`
(script.js) → `chrome.runtime.sendMessage` → `background.js` message listener
(background.js:455) which reads `obj = utils.filterMessage(message, "banSource",
"banMode")` and builds `wrapperProcessHandler = processHandler.bind(null,
obj.banSource, obj.banMode, obj.entryUrl, obj.authorName, obj.authorId,
obj.targetType, obj.clickSource, obj.titleName, obj.titleId,
obj.timeSpecifier)` (background.js:461), then `handleProcessQueue` enqueues it.
This is the exact shape "Tekrarla" needs to replay.

Telemetry: `commHandler.sendAnalyticsData(data)` (commHandler.js:184) POSTs to the
backend `analytics` view (`client_data_collector/views.py:99`), which
`get_or_create`s a `ClickType` row from `data.click_type` and stores a
`ClientAnalytic` (`client_data_collector/models.py:76`). `ClickType` is an
open string lookup table (`api/admin.py` registers it via `LookupAdmin`), so a
new `enums.ClickType` value needs zero backend schema change — it just appears as
a new row the first time it's POSTed, matching how every other `ClickType` value
already works (e.g. `SETTINGS_TOGGLE`, `AUTHOR_LIST_ACTION`).

## Goals / Non-Goals

**Goals:**
- Retry a FAILED, COMPLETED, or QUEUED task from İşlem durumu using its original
  parameters, through the existing enqueue path — no new execution pipeline.
- Navigate straight to the entry/profile/title a task acted on, working even when
  that user/title is already blocked or muted.
- Make a retry click distinguishable from a fresh action in `Action`/`ClientAnalytic`
  telemetry, viewable in the existing Django admin.

**Non-Goals:**
- No change to how tasks execute, retry-on-failure-within-a-run logic
  (`performWithRetry`, background.js:652 — that's an in-flight HTTP retry, not
  this user-facing "run this task again" feature), or the resumable-operation
  state machine (`resumableOperation.js`).
- No de-duplication of repeated task history rows, no cap/rotation policy changes
  for how many completed tasks are retained.
- No android/ work — this screen doesn't exist there in the same form.
- Bulk-operation queue items (BanSource.DATE_BASED_BULK, BLOCK_MUTED_USERS,
  REFRESH_*, migrations) don't have a single target user/title/entry to "Git" to;
  "Git" is only offered when the task's stored target fields are present, "Tekrarla"
  is offered for all task kinds that came through the per-target
  `EksiEngel_sendMessage` path.

## Decisions

**Persist target identity on the process record itself, not just in the handler
closure.** `createWrapperProcessHandler` already accepts a `metadata` object
attached to the wrapper (background.js:192-201); extend that metadata with the
raw `entryUrl`/`authorName`/`authorId`/`targetType`/`clickSource`/`titleName`/
`titleId`/`timeSpecifier` for every wrapper built from a single-target dispatch
(background.js:461 path), and thread the same fields into whatever record
`insertCompletedProcessesTable` persists for history. Alternative considered:
re-derive the target by re-parsing `operationNotes` display strings — rejected,
that string is for humans and already drops precision (e.g. it doesn't retain
`authorId`, only a display name).

**Reuse `EksiEngel_sendMessage`'s message shape for retry, sent directly to
background.js, bypassing the content-script menu entirely.** The notification
page already talks to the background service worker over
`chrome.runtime.connect(null, { name: 'notification-page' })` (notification.js:6)
and via `chrome.runtime.sendMessage` (`sendMessageWithPromise`,
notification.js:665). "Tekrarla" builds the same message object
`EksiEngel_sendMessage` would have sent and posts it the same way — background.js's
existing listener at background.js:455 needs no new message type, only the
already-present `banSource`/`banMode`/... fields read from the stored record
instead of a live DOM click.

**"Git" opens a direct URL, not a page search.** Entry: `${origin}/entry/{entryId}`
(same construction as script.js's `entryUrl`). Profile: `${origin}/biri/{authorName}`.
Title: `${origin}/{titleSlugOrId}` per the existing title-URL convention used
elsewhere in the codebase (mirror whatever `titleId`/`titleName`-to-URL logic
`processHandler`/scrapingHandler already use for titles, rather than inventing a
new one). Opened via `chrome.tabs.create`, matching `handleOpenFaq`/
`handleOpenNotification` (popup.js:24-29). This resolves correctly even against a
blocked/muted target because it's a plain URL navigation, not something that
depends on the target being visible in a list/page the extension has already
rendered.

**New `ClickType.OPERATION_RETRY` value, sent once per "Tekrarla" click**, via
`commHandler.sendAnalyticsData({ click_type: enums.ClickType.OPERATION_RETRY })`
— same call shape as every existing site (e.g. background.js:1100). This makes
retry volume visible as its own `ClickType`/`ClientAnalytic` row in
`client_data_collector/admin.py`, without touching the `Action` model (which
records the *outcome* of a run, not the click that triggered it — the retried run
itself still produces its own ordinary `Action` row exactly as a first attempt
would, since it goes through the same `processHandler`).

## Risks / Trade-offs

[Old task records in local storage predate this change and won't have the new
target-identity fields] → "Git"/"Tekrarla" degrade gracefully: if the fields are
absent, hide both buttons for that row rather than erroring. Only tasks created
after this change ship will have them.

[Retrying a task whose author/title has since been deleted or renamed on
Ekşi Sözlük] → Same failure mode as a fresh action against a stale target; no new
handling needed beyond what `processHandler` already does for a 404/gone target.

[Bulk/list-sourced tasks (author list imports, date-based bulk) have no single
"Git" target and multiple original targets for "Tekrarla"] → Explicitly excluded
per Non-Goals; UI only renders the buttons when the stored record has the
single-target fields.

## Migration Plan

Pure additive change to extension-local state shape (queue/history record
metadata) and one new `ClickType` enum value. No stored data migration: existing
persisted queue/history entries simply won't have the new fields and the UI treats
that as "buttons not available" (see Risks). No backend migration — `ClickType` is
looked up by string, not a fixed choice. Ship as a normal extension release; no
rollback complexity beyond a normal version bump.

## Open Questions

- Exact title→URL construction to reuse for "Git" on a TITLE-targeted task —
  confirm against the current title-page URL scheme used elsewhere (e.g. wherever
  `enums.BanSource.TITLE`/`TargetType.TITLE` tasks build their target URL today)
  during implementation, rather than assuming `/{titleId}` here.
- Whether "Tekrarla" on a still-QUEUED (not yet started) task should be offered at
  all, given it would just duplicate an already-pending item — default to yes
  (simplest, most predictable) unless implementation finds it confusing in
  practice.
