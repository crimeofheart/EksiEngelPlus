## ADDED Requirements

### Requirement: Paged HTML endpoints terminate on 404

`GET /{slug}--{id}?p={n}` and `GET /{slug}--{id}?a=dailynice&p={n}` answer **404**
past the last page. They do not answer 200 with an empty entry list.

A client SHALL treat that 404 as the end of pagination and keep everything
already collected. Measured on `/yeni-parti--473428?a=dailynice&p=2`, a title
with a single page of daily entries.

This is the opposite of the JSON list endpoints the contract already describes,
which answer 200 with `IsLast` or an empty array and never 404 for being past the
end. The two families cannot share a termination rule, and a client that carries
one across to the other fails in whichever direction it guessed: waiting for an
empty page that never comes, or stopping on an `IsLast` that is not there.

A 404 on the **first** page SHALL remain an error. Every title that exists
renders page one, so a 404 there means the slug or the id is wrong. Returning an
empty result would turn a malformed request into an operation that silently does
nothing, which is the harder failure to notice of the two.

Only 404 SHALL end pagination. A client SHALL NOT treat an arbitrary failure as
the last page: it cannot then distinguish "no more pages" from "the request
failed", and a 500 halfway through a long title becomes a short list acted on as
though it were complete. The extension does exactly this
(`scrapingHandler.js:1227-1230`) and is the reason to name the rule rather than
copy the behaviour.

#### Scenario: The page after the last one

- **WHEN** page `n+1` of a title with `n` pages is fetched
- **THEN** the response is 404, and the client stops with the authors from pages 1..n

#### Scenario: A title that does not exist

- **WHEN** page 1 is fetched for a wrong slug or id
- **THEN** the 404 surfaces as an error rather than as an empty result

#### Scenario: A failure that is not 404

- **WHEN** a page fetch fails with any other status
- **THEN** the client SHALL NOT treat it as the end of pagination

#### Scenario: The JSON endpoints are unaffected

- **WHEN** `/relation-list`, `/follower` or `/following` is paged past its end
- **THEN** it answers 200 with `IsLast` or an empty array, and the 404 rule does not apply
