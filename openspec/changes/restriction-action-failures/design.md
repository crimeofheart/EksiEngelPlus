## Context

`frontend/app/assets/js/relationHandler.js:95` returns SUCCESS for non-rate-limit failures; `:176` returns a scalar on HTTP errors instead of the expected object. `programController.js:1028` trusts that result before unmuting. `programController.js:933` walks a list it subsequently shrinks. `programController.js:827` removes the block before adding the mute. Android's `Tasks.kt:638` has that same conversion order. `ops/runtime/.../di/OpsModule.kt:136` wires muted-to-blocked as a single block task, so the mute is never removed.

## Goals / Non-Goals

Goals: preserve protection when a mutation fails, isolate rejected or inaccessible targets, keep accurate progress, and carry failure details through existing logs.

Non-goals: reinterpret unknown numeric site codes; new UI, dependency, or release machinery.

## Decisions

- Return FAIL with counters for permanent request failures; attach retryAfter only for 429 so all callers distinguish rejection from cooldown. Keep numeric 0 and 2 accepted for additions and JSON `{ "result": true }` accepted for removals. Android keeps its existing sealed result types.
- Use IDs from collected relation pages, avoiding unnecessary inaccessible profile reads. Snapshot muted targets before mutations rather than incrementing a page after removing its members.
- Wire Android muted-to-blocked to a dedicated two-step task rather than a single block.
- Add mute before removing block, mirroring block-before-unmute. A failed second step can leave both restrictions, retaining protection and allowing an idempotent retry.
- Gate subsequent actions on the immediate result, never cumulative successes from earlier users. Android follow preparation must also stop on an unsuccessful lift or lost session.
- Catch profile failures at the target boundary. Log unknown HTTP/numeric outcomes without assuming they mean the target blocked us.

## Risks / Trade-offs

- Unknown site outcomes remain failures; request logs retain their actual status/code for diagnosis.
- Interrupted conversion can leave both restrictions. This is deliberate protection; subsequent conversion retries accepted additions without removing protection first.
- Full collection increases memory in proportion to targets, matching Android's existing snapshot behavior.
- Android removal parsing is changed from a regex fragment match to full JSON parsing using the existing serialization dependency.
- Existing extension restart-based conversion resume remains; successful targets disappear from the source list and failed/partially converted targets remain available.
