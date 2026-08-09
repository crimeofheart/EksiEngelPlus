## 1. Preparation

- [ ] 1.1 Designate a throwaway Ekşi Sözlük account as the actor and a second controlled account as the mutation target — never a third party's
- [ ] 1.2 Record the actor's starting relation state so every mutation made here can be proven reversed afterwards
- [ ] 1.3 Confirm the harness logs full request and response, including all headers, since the header presence question cannot be answered from a body

## 2. Measure the limit

Carried over from `android-spike` section 4, which never ran.

- [ ] 2.1 Drive mutations against the controlled target at a fixed cadence until a 429 is returned; record the count and the elapsed time
- [ ] 2.2 Repeat the run at least once to establish whether the limit is a fixed count, a sliding window, or varies
- [ ] 2.3 Record whether `Retry-After` is present, and whether it is integer seconds or an HTTP date
- [ ] 2.4 Confirm the actual cooldown by retrying at the advertised time and recording success or a further 429
- [ ] 2.5 If a further 429 arrives, bisect the true cooldown rather than recording the advertised one
- [ ] 2.6 Compare the measured limit against the 12/min figure in `frontend/app/assets/js/notificationHandler.js:60` and record the discrepancy if any
- [ ] 2.7 Reverse every mutation performed in this section and verify against the state recorded in 1.2

## 3. Record it

- [ ] 3.1 Write `docs/android/rate-limit-measurement.md` — observation date, account pair, cadence, request count, elapsed time, verbatim 429 response including headers
- [ ] 3.2 State explicitly whether `Retry-After` was present, absent, or inconsistent across runs
- [ ] 3.3 Record the measurement as a point-in-time observation of a server that can change, not as a permanent truth
- [ ] 3.4 Note the reversal of every mutation, so the document is also the audit trail

## 4. Reconcile the code

- [ ] 4.1 Point the action pacer's rate at the measured figure; if it is 12/min, say so in the config comment with a link to the document rather than leaving it unsourced
- [ ] 4.2 Replace the 429 no-header fallback with the measured cooldown
- [ ] 4.3 Resolve an HTTP-date `Retry-After` against the response's `Date` header rather than the device clock
- [ ] 4.4 If the measured limit differs from 12, update `frontend/app/assets/js/notificationHandler.js:60` in the same commit so the two never disagree
- [ ] 4.5 Add a unit test that the configured rate matches the documented one, so a future edit to either side fails loudly

## 5. Close out

- [ ] 5.1 Confirm every mutation is reversed on both accounts
- [ ] 5.2 `cd android && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest :app:lintDebug`
- [ ] 5.3 If `frontend/app/` was touched: `cd frontend/app && npm run check && npm run package`
- [ ] 5.4 Run `openspec validate android-rate-limit-measurement` clean, then `openspec archive android-rate-limit-measurement`
