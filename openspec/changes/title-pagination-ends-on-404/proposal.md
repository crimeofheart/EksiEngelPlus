## Why

`eksisozluk-client-contract` documents the two paged title endpoints and their
selectors, and says nothing about how a client knows it has reached the end. The
Android client guessed: it paginated until a page yielded no authors.

That page never comes. Ekşi answers **404** past the last page of a title, so the
terminator the loop waited for did not exist. `get()` threw, the exception left
the scrape and ended the whole operation. Reported from a device on
`/yeni-parti--473428?a=dailynice&p=2` — a title with one page of daily entries.
The run showed "işlem başarısız" having acted on nobody, with page one's authors
already in hand and discarded.

The extension survives this by accident rather than by contract: every paginator
catches every error and calls it the last page
(`scrapingHandler.js:1227-1230`, `:808`, `:858`, `:952`). It works, and it cannot
distinguish "no more pages" from "the network went away" — so a mid-pagination
500 silently ends the scrape and the extension acts on a fraction of a title
while reporting success. One client failed loudly, the other fails quietly, and
neither was told what the terminator is.

The fix has shipped ahead of this change, as v0.1.8, because a released build was
ending user operations. This records the site behaviour that made it necessary,
so the next client — or the next paginated endpoint — does not have to rediscover
it from a bug report.

## What Changes

- Record in the contract that HTML paged endpoints terminate on 404, and that
  this differs from the JSON list endpoints, which terminate on `IsLast` or an
  empty array and are the ones the contract already describes.
- Require that a 404 on the **first** page stays an error: every real title
  renders page one, so a 404 there means the slug or the id is wrong.
- Require that only 404 ends pagination. Treating every failure as the end is
  what makes truncation silent.

## Impact

- Affected specs: `eksisozluk-client-contract`
- Affected code: `ScrapeClient.allTopicAuthors`, `ScrapeClient.get` — already
  shipped in v0.1.8 (`3a42c77`)
- `frontend/app/` is unchanged. The extension already stops at 404; its
  over-broad catch is a separate defect, named here and deliberately not fixed
  under this change.
