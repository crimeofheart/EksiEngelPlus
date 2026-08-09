## 1. The fix

Shipped as `3a42c77` in v0.1.8, ahead of this change, because a released build
was ending user operations. Recorded here rather than backdated.

- [x] 1.1 Give `get()` an exception carrying the status code — it threw a plain `IOException` with the code in its message, so acting on a status meant parsing English out of a string, which is why nobody did
- [x] 1.2 End `allTopicAuthors` pagination on a 404 past the first page, keeping what was collected
- [x] 1.3 Keep a 404 on the first page an error, so a wrong slug or id cannot become an operation that silently does nothing
- [x] 1.4 Let every other status propagate, so a 500 halfway is never mistaken for the end
- [x] 1.5 Confirm the other paginators are unaffected: `allRelations` ends on `IsLast`, `allFollow` on an empty array, both JSON and neither able to 404 for being past the end

## 2. Tests

- [x] 2.1 A 404 past the last page ends pagination and keeps page one's authors
- [x] 2.2 A 404 on the first page raises
- [x] 2.3 A 500 mid-pagination raises rather than truncating
- [x] 2.4 An empty page still ends pagination, so the old terminator is not lost

## 3. The extension

- [x] 3.1 Audit every paginator in `scrapingHandler.js` — all four catch and return `isLast`, so none dies on the 404
- [ ] 3.2 Decide whether to narrow that catch to 404. It cannot currently tell "no more pages" from "the request failed", so a mid-pagination 500 ends the scrape and the extension acts on a fraction of a title while reporting success. Untouched deliberately: four call sites in shipped code with no test suite, and the failure is quiet rather than destructive. This is the decision, not the work.

## 4. Close out

- [x] 4.1 `./gradlew test testDebugUnitTest lintDebug`
- [x] 4.2 Released in v0.1.8 with all four artifacts signed
- [ ] 4.3 Verify on device that a title run now completes and acts on page one's authors — the reported case, `yeni-parti`, is the one to retry
- [ ] 4.4 Run `openspec validate title-pagination-ends-on-404` clean, then `openspec archive title-pagination-ends-on-404`
